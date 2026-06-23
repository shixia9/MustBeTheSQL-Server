package com.sql.logic.engine.domain.agent.core;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core executor for the SQL Agent StateGraph (Phase 4).
 * <p>
 * Compiles the graph ONCE at construction with a {@link MemorySaver} and
 * {@code interruptBefore(HITL)}, so the HITL node can pause the graph. Each user
 * request runs on its own {@code threadId} (distinct checkpoint), and
 * {@link #resume(String, boolean, String, Long)} continues execution from that
 * checkpoint after the human decision is injected via {@code CompiledGraph.updateState}.
 * <p>
 * The controller inspects {@link AgentRunHandle#isHaltedAtHitl()} after the stream
 * completes to decide whether to emit an {@code AWAITING_CONFIRMATION} SSE event
 * (paused) or the normal {@code COMPLETED} event.
 */
@Service
public class SqlAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SqlAgentRunner.class);

    private final CompiledGraph compiledGraph;
    private final HitlSessionRegistry hitlSessionRegistry;

    public SqlAgentRunner(StateGraph sqlAgentGraph, MemorySaver sqlAgentMemorySaver,
                          HitlSessionRegistry hitlSessionRegistry) {
        this.hitlSessionRegistry = hitlSessionRegistry;
        try {
            this.compiledGraph = sqlAgentGraph.compile(CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(sqlAgentMemorySaver).build())
                    .interruptBefore(SqlAgentSpec.Node.HITL)
                    .build());
            log.info("[SqlAgentRunner] Graph compiled with MemorySaver + interruptBefore(HITL). "
                    + "Phase 4 chain: START → EVIDENCE_RECALL → SCHEMA_LINKING → FEASIBILITY → PLANNER → "
                    + "HITL_GATE → [HITL interrupt] → PLAN_DISPATCH ⇄ {SQL_GENERATION→SQL_EXECUTION↔SQL_FIXER | "
                    + "PYTHON_GENERATION→PYTHON_EXECUTION→PYTHON_ANALYSIS} → REPORT → END");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile SQL Agent StateGraph", e);
        }
    }

    /**
     * Start a new graph execution on a fresh threadId, returning the streaming handle.
     * The handle's {@code runnableConfig} owns the checkpoint a subsequent
     * {@link #resume(String, boolean, String, Long)} continues from.
     */
    public AgentRunHandle execute(Long connectionId, String userInput, Long userId,
                                  Long llmConfigId, List<String> tableNames, boolean autoConfirm) {
        String threadId = UUID.randomUUID().toString();
        RunnableConfig rc = RunnableConfig.builder().threadId(threadId).build();

        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put(SqlAgentSpec.StateKey.INPUT, userInput);
        initialState.put(SqlAgentSpec.StateKey.USER_ID, userId);
        initialState.put(SqlAgentSpec.StateKey.CONNECTION_ID, connectionId);
        initialState.put(SqlAgentSpec.StateKey.LLM_CONFIG_ID, llmConfigId);
        initialState.put(SqlAgentSpec.StateKey.DB_TYPE, "");
        initialState.put(SqlAgentSpec.StateKey.TABLE_NAMES, tableNames != null ? tableNames : List.of());
        initialState.put(SqlAgentSpec.StateKey.AUTO_CONFIRM, autoConfirm);
        initialState.put(SqlAgentSpec.StateKey.REPAIR_COUNT, 1);

        log.info("[SqlAgentRunner] Starting graph: threadId={}, autoConfirm={}, connectionId={}, userId={}, input='{}'",
                threadId, autoConfirm, connectionId, userId, userInput);

        Flux<NodeOutput> flux = compiledGraph.stream(initialState, rc)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(output -> log.info("[SqlAgentRunner] Node completed: {}", output.node()))
                .doOnComplete(() -> log.info("[SqlAgentRunner] Graph execution (threadId={}) stream complete", threadId))
                .doOnError(e -> log.error("[SqlAgentRunner] Graph execution (threadId={}) error", threadId, e));

        AgentRunContext context = new AgentRunContext(threadId, userId, connectionId, llmConfigId, tableNames, autoConfirm, rc);
        return new AgentRunHandle(threadId, context, rc, flux);
    }

    /** Convenience overload (autoConfirm defaults to true — Phase 3 behaviour preserved). */
    public AgentRunHandle execute(Long connectionId, String userInput, Long userId,
                                  Long llmConfigId, List<String> tableNames) {
        return execute(connectionId, userInput, userId, llmConfigId, tableNames, true);
    }

    /**
     * Resume a paused HITL session by injecting the human decision and continuing the
     * graph from its checkpoint. The registered session must belong to {@code userId}.
     *
     * @return the resumed streaming handle, or {@code null} if no matching session exists
     *         (expired, already consumed, or owned by another user).
     */
    public AgentRunHandle resume(String threadId, boolean approved, String feedback, Long userId) {
        AgentRunContext context = hitlSessionRegistry.get(threadId).orElse(null);
        if (context == null) {
            log.warn("[SqlAgentRunner] resume: no pending session for threadId={}", threadId);
            return null;
        }
        if (userId != null && !userId.equals(context.getUserId())) {
            log.warn("[SqlAgentRunner] resume: userId {} does not own threadId={}", userId, threadId);
            return null;
        }

        RunnableConfig rc = context.getRunnableConfig();
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(SqlAgentSpec.StateKey.CONFIRMATION_APPROVED, approved);
        update.put(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, feedback == null ? "" : feedback);

        try {
            RunnableConfig resumed = compiledGraph.updateState(rc, update);
            log.info("[SqlAgentRunner] Resuming threadId={} approved={}", threadId, approved);
            Flux<NodeOutput> flux = compiledGraph.stream(null, resumed)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(output -> log.info("[SqlAgentRunner] Resumed node completed (threadId={}): {}", threadId, output.node()))
                    .doOnComplete(() -> {
                        log.info("[SqlAgentRunner] Resume stream complete (threadId={})", threadId);
                        hitlSessionRegistry.remove(threadId);
                    })
                    .doOnError(e -> log.error("[SqlAgentRunner] Resume stream error (threadId={})", threadId, e));
            return new AgentRunHandle(threadId, context, resumed, flux);
        } catch (Exception e) {
            log.error("[SqlAgentRunner] Failed to resume threadId={}", threadId, e);
            return null;
        }
    }

    public CompiledGraph getCompiledGraph() {
        return compiledGraph;
    }

    public HitlSessionRegistry getHitlSessionRegistry() {
        return hitlSessionRegistry;
    }

    /**
     * Streaming handle for one run. Exposes the threadId, the {@link Flux} of node outputs,
     * and {@link #isHaltedAtHitl()} which the controller calls after the flux completes to
     * detect the HITL pause (and register the session for resume).
     */
    public class AgentRunHandle {

        private final String threadId;
        private final AgentRunContext context;
        private final RunnableConfig runnableConfig;
        private final Flux<NodeOutput> flux;

        AgentRunHandle(String threadId, AgentRunContext context, RunnableConfig runnableConfig, Flux<NodeOutput> flux) {
            this.threadId = threadId;
            this.context = context;
            this.runnableConfig = runnableConfig;
            this.flux = flux;
        }

        public String getThreadId() { return threadId; }
        public AgentRunContext getContext() { return context; }
        public RunnableConfig getRunnableConfig() { return runnableConfig; }
        public Flux<NodeOutput> getFlux() { return flux; }

        /**
         * After the stream completes, returns true if the graph is paused right before
         * the HITL node (awaiting human confirmation). Registers the run context for
         * resume in that case. Returns false on a normal finish.
         */
        public boolean isHaltedAtHitl() {
            try {
                StateSnapshot snapshot = compiledGraph.getState(runnableConfig);
                String next = snapshot == null ? null : snapshot.next();
                boolean halted = SqlAgentSpec.Node.HITL.equals(next);
                if (halted) {
                    hitlSessionRegistry.register(context);
                }
                log.debug("[SqlAgentRunner] isHaltedAtHitl(threadId={}) → next='{}' halted={}", threadId, next, halted);
                return halted;
            } catch (Exception e) {
                log.warn("[SqlAgentRunner] getState failed for threadId={}: {}", threadId, e.getMessage());
                return false;
            }
        }
    }
}