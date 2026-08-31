package com.sql.logic.engine.domain.agent.core;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.sql.logic.engine.application.service.DatabaseMetaDataService;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agentic.config.AgentOrchestrator;
import com.sql.logic.engine.domain.agentic.enrichment.SchemaEnrichmentService;
import com.sql.logic.engine.domain.memory.MemoryDomainService;
import com.sql.logic.engine.domain.skill.SkillInvocationResolver;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.domain.trace.TraceContextRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Streaming executor for the 6-Agent Multi-Agent system (AgentOrchestrator).
 */
@Service
public class AgenticRunner {

    private static final Logger log = LoggerFactory.getLogger(AgenticRunner.class);

    private final CompiledGraph compiledGraph;
    private final AgentOrchestrator orchestrator;
    private final HitlSessionRegistry hitlSessionRegistry;
    private final TraceContextRegistry traceContextRegistry;
    private final NodeStartedSinkRegistry sinkRegistry;
    private final AgentEventSinkRegistry eventSinkRegistry;
    private final AgentSseCodec codec;
    private final DatabaseMetaDataService databaseMetaDataService;
    private final SchemaEnrichmentService schemaEnrichmentService;
    private final MemoryDomainService memoryDomainService;
    private final SkillInvocationResolver skillInvocationResolver;

    public AgenticRunner(AgentOrchestrator orchestrator,
                         RedisSaver redisSaver,
                         HitlSessionRegistry hitlSessionRegistry,
                         TraceContextRegistry traceContextRegistry,
                         AgentSseStartedListener sseStartedListener,
                         NodeStartedSinkRegistry sinkRegistry,
                         AgentEventSinkRegistry eventSinkRegistry,
                         AgentSseCodec codec,
                         DatabaseMetaDataService databaseMetaDataService,
                         SchemaEnrichmentService schemaEnrichmentService,
                         MemoryDomainService memoryDomainService,
                         SkillInvocationResolver skillInvocationResolver) {
        this.orchestrator = orchestrator;
        this.hitlSessionRegistry = hitlSessionRegistry;
        this.traceContextRegistry = traceContextRegistry;
        this.sinkRegistry = sinkRegistry;
        this.eventSinkRegistry = eventSinkRegistry;
        this.codec = codec;
        this.databaseMetaDataService = databaseMetaDataService;
        this.schemaEnrichmentService = schemaEnrichmentService;
        this.memoryDomainService = memoryDomainService;
        this.skillInvocationResolver = skillInvocationResolver;
        try {
            this.compiledGraph = orchestrator.compile(CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(redisSaver).build())
                    .interruptBefore(SqlAgentSpec.Node.HITL)
                    .withLifecycleListener(sseStartedListener)
                    .build());
            log.info("[AgenticRunner] Agentic graph compiled with Redis checkpoint + interruptBefore(HITL). ");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile Agentic StateGraph", e);
        }
    }

    /**
     * Start a new 6-Agent execution on a fresh threadId.
     */
    public AgentRunHandle execute(Long connectionId, String userInput, Long userId,
                                   Long llmConfigId, Long workspaceId, List<String> tableNames,
                                   String schemaName, boolean autoConfirm) {
        return execute(connectionId, userInput, userId, llmConfigId, workspaceId,
                tableNames, schemaName, autoConfirm, null, "", false);
    }

    /**
     * Full overload with multi-turn conversation support.
     */
    public AgentRunHandle execute(Long connectionId, String userInput, Long userId,
                                   Long llmConfigId, Long workspaceId, List<String> tableNames,
                                   String schemaName, boolean autoConfirm,
                                   Long conversationId, String conversationHistory,
                                   boolean htmlReport) {
        return execute(connectionId, userInput, userId, llmConfigId, workspaceId,
                tableNames, schemaName, autoConfirm, conversationId, conversationHistory,
                htmlReport, null);
    }

    /**
     * Full overload with multi-turn conversation support and an optional direct
     * tool invocation payload.
     */
    public AgentRunHandle execute(Long connectionId, String userInput, Long userId,
                                   Long llmConfigId, Long workspaceId, List<String> tableNames,
                                   String schemaName, boolean autoConfirm,
                                   Long conversationId, String conversationHistory,
                                   boolean htmlReport, Map<String, Object> toolInvocation) {
        String threadId = UUID.randomUUID().toString();
        RunnableConfig rc = RunnableConfig.builder().threadId(threadId).build();

        // Resolve a "/" skill invocation (e.g. "/skillName <task>" submitted via
        // the command palette's inject_prompt path) into the skill's rendered
        // prompt before entering the graph. Skipped for direct toolInvocation
        // (palette call_tool selections) which take the MCP short-circuit path.
        // `input` is kept effectively final so it can be captured by the
        // background enrichment lambda below.
        SkillInvocationResolver.Result skillResult = (toolInvocation == null && skillInvocationResolver != null)
                ? skillInvocationResolver.resolve(userId, userInput)
                : SkillInvocationResolver.Result.notResolved();
        final String input = skillResult.resolved() ? skillResult.resolvedInput() : userInput;
        if (skillResult.resolved()) {
            log.info("[AgenticRunner] '/' skill invocation resolved: skill={}, userId={}",
                    skillResult.skillName(), userId);
        }

        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put(SqlAgentSpec.StateKey.INPUT, input);
        initialState.put(SqlAgentSpec.StateKey.USER_ID, userId);
        initialState.put(SqlAgentSpec.StateKey.CONNECTION_ID, connectionId);
        initialState.put(SqlAgentSpec.StateKey.LLM_CONFIG_ID, llmConfigId);
        initialState.put(SqlAgentSpec.StateKey.TABLE_NAMES, tableNames != null ? tableNames : List.of());
        initialState.put(SqlAgentSpec.StateKey.SCHEMA_NAME, schemaName != null ? schemaName : "");
        initialState.put(SqlAgentSpec.StateKey.AUTO_CONFIRM, autoConfirm);
        initialState.put(SqlAgentSpec.StateKey.WORKSPACE_ID, workspaceId);
        initialState.put(SqlAgentSpec.StateKey.THREAD_ID, threadId);
        initialState.put(SqlAgentSpec.StateKey.CONVERSATION_ID, conversationId);
        initialState.put(SqlAgentSpec.StateKey.CONVERSATION_HISTORY,
                conversationHistory != null ? conversationHistory : "");
        initialState.put(SqlAgentSpec.StateKey.REPAIR_COUNT, 0);
        initialState.put(SqlAgentSpec.StateKey.HITL_VERSION, 0);
        initialState.put("htmlReport", htmlReport);

        // propagate a direct tool invocation (from the "/" command palette) into state.
        if (toolInvocation != null && !toolInvocation.isEmpty()) {
            initialState.put(SqlAgentSpec.StateKey.TOOL_INVOCATION, toolInvocation);
            log.info("[AgenticRunner] Direct toolInvocation present (toolName={}), "
                    + "ManagerAgent will short-circuit to ToolAssistantAgent",
                    toolInvocation.get("toolName"));
        }

        // Recall cross-session long-term memories
        List<Map<String, Object>> recalledMemories = (memoryDomainService != null && userId != null)
                ? memoryDomainService.searchRelevant(userId, null, input, 5)
                : List.of();
        String userMemorySection = formatRecalledMemories(recalledMemories);
        initialState.put(SqlAgentSpec.StateKey.USER_MEMORY, userMemorySection);

        // Quick fallback schema (fast, no LLM) — available immediately for ManagerAgent routing.
        // The enriched (LLM-filtered) version is built on a background thread and becomes
        // available to downstream agents (DataScientist, etc.) when ready.
        String quickSchema = schemaEnrichmentService.buildQuickFallback(connectionId, tableNames, schemaName);
        initialState.put(SqlAgentSpec.StateKey.SCHEMA_DDL, quickSchema);

        // Resolve actual database dialect type from connection config
        String dbType = resolveDbType(connectionId);
        initialState.put(SqlAgentSpec.StateKey.DB_TYPE, dbType);

        log.info("[AgenticRunner] Starting 6-Agent graph: threadId={}, autoConfirm={}, connectionId={}, userId={}, llmConfigId={}, input='{}'",
                threadId, autoConfirm, connectionId, userId, llmConfigId, input);

        TraceContext traceContext = new TraceContext(threadId, userId, workspaceId);
        traceContextRegistry.register(threadId, traceContext);
        // RedisSaver serializes the complete graph state. Runtime-only values such
        // as TraceContext and CompletableFuture must never enter that state. The
        // quick schema is checkpoint-safe; tracing remains in the local registry.

        Sinks.Many<String> startedSink = sinkRegistry.register(threadId);
        Sinks.Many<String> customSink = eventSinkRegistry.register(threadId);
        Flux<NodeOutput> graphFlux = compiledGraph.stream(initialState, rc)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(output -> log.info("[AgenticRunner] Agent node completed: {}", output.node()))
                .doOnError(e -> log.error("[AgenticRunner] Graph error (threadId={})", threadId, e));

        AgentRunContext context = new AgentRunContext(threadId, userId, connectionId, llmConfigId,
                workspaceId, tableNames, schemaName, autoConfirm, rc);
        context.setTraceContext(traceContext);
        context.setConversationId(conversationId);

        Flux<String> unified = buildUnifiedSse(graphFlux, startedSink, customSink, threadId, context);
        return new AgentRunHandle(threadId, context, rc, graphFlux, unified);
    }

    /**
     * Resume a HITL-paused session by updating its Redis checkpoint and
     * continuing the same compiled graph.
     */
    public AgentRunHandle resume(String threadId, boolean approved, String feedback, Long userId) {
        return resume(threadId, approved, feedback, userId, null);
    }

    /** Resume with an optional optimistic HITL version check. */
    public AgentRunHandle resume(String threadId, boolean approved, String feedback,
                                 Long userId, Integer expectedHitlVersion) {
        return resumeResult(threadId, approved, feedback, userId, expectedHitlVersion).handle();
    }

    /**
     * Resume with a protocol-level result. The legacy resume methods above
     * remain for callers that only need a handle.
     */
    public ResumeResult resumeResult(String threadId, boolean approved, String feedback,
                                     Long userId, Integer expectedHitlVersion) {
        HitlSessionRegistry.ClaimResult claim = hitlSessionRegistry.claimDetailed(threadId, userId);
        if (claim.status() != HitlSessionRegistry.ClaimStatus.CLAIMED) {
            log.warn("[AgenticRunner] resume rejected threadId={} status={}", threadId, claim.status());
            return ResumeResult.failure(claim.status().name(), claim.message());
        }
        AgentRunContext context = claim.context();
        RunnableConfig rc = context.getRunnableConfig();
        try {
            StateSnapshot pending = compiledGraph.getState(rc);
            int currentVersion = readInt(pending.state(), SqlAgentSpec.StateKey.HITL_VERSION, 0);
            if (expectedHitlVersion != null && expectedHitlVersion != currentVersion) {
                hitlSessionRegistry.restore(context);
                return ResumeResult.failure("STALE_VERSION", "HITL version is stale");
            }
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(SqlAgentSpec.StateKey.CONFIRMATION_APPROVED, approved);
            update.put(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK,
                    feedback == null ? "" : feedback.trim());
            update.put(SqlAgentSpec.StateKey.HITL_VERSION, currentVersion + 1);
            update.put(SqlAgentSpec.StateKey.HITL_STATUS, "DECISION_SUBMITTED");

            RunnableConfig resumed = compiledGraph.updateState(rc, update).withResume();
            context.setRunnableConfig(resumed);
            log.info("[AgenticRunner] Resumed threadId={} approved={} version={}",
                    threadId, approved, currentVersion + 1);

            Sinks.Many<String> startedSink = sinkRegistry.register(threadId);
            Sinks.Many<String> customSink = eventSinkRegistry.register(threadId);
            Flux<NodeOutput> graphFlux = compiledGraph.stream(null, resumed)
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(output -> log.info("[AgenticRunner] Resumed agent node completed (threadId={}): {}", threadId, output.node()))
                    .doOnComplete(() -> log.info("[AgenticRunner] Resume stream complete (threadId={})", threadId))
                    .doOnError(e -> log.error("[AgenticRunner] Resume stream error (threadId={})", threadId, e));

            Flux<String> unified = buildUnifiedSse(graphFlux, startedSink, customSink, threadId, context);
            return ResumeResult.success(new AgentRunHandle(threadId, context, resumed, graphFlux, unified));
        } catch (Exception e) {
            // Claim is destructive only for the pending envelope. Restore it if
            // checkpoint update failed so a transient Redis error is retryable.
            hitlSessionRegistry.register(context);
            log.error("[AgenticRunner] Failed to resume threadId={}", threadId, e);
            return ResumeResult.failure("CHECKPOINT_ERROR", "Failed to update HITL checkpoint");
        }
    }

    public record ResumeResult(AgentRunHandle handle, String code, String message) {
        static ResumeResult success(AgentRunHandle handle) {
            return new ResumeResult(handle, "", "");
        }

        static ResumeResult failure(String code, String message) {
            return new ResumeResult(null, code, message);
        }

        public boolean succeeded() { return handle != null; }
    }

    private Flux<String> buildUnifiedSse(Flux<NodeOutput> graphFlux, Sinks.Many<String> startedSink,
                                         Sinks.Many<String> customSink,
                                         String threadId, AgentRunContext runContext) {
        Flux<String> finished = graphFlux.map(o -> codec.nodeOutputToJson(o, runContext))
                .filter(s -> !s.isEmpty())
                .doFinally(sig -> {
                    startedSink.tryEmitComplete();
                    customSink.tryEmitComplete();
                });
        return Flux.merge(finished, startedSink.asFlux(), customSink.asFlux())
                .doFinally(sig -> {
                    sinkRegistry.remove(threadId);
                    eventSinkRegistry.remove(threadId);
                });
    }

    public CompiledGraph getCompiledGraph() {
        return compiledGraph;
    }

    /**
     * Resolve the database dialect type from the connection configuration.
     */
    private String resolveDbType(Long connectionId) {
        if (connectionId == null || connectionId <= 0) return "";
        try {
            return databaseMetaDataService.getDbType(connectionId);
        } catch (Exception e) {
            log.debug("[AgenticRunner] Failed to resolve dbType: {}", e.getMessage());
        }
        return "mysql";
    }

    /**
     * Format recalled long-term memories into a compact section for the system prompt.
     */
    private String formatRecalledMemories(List<Map<String, Object>> memories) {
        if (memories == null || memories.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("## 用户偏好与历史洞察\n");
        for (Map<String, Object> m : memories) {
            Object content = m.get("content");
            if (content == null || content.toString().isBlank()) continue;
            Object type = m.get("type");
            String tag = (type != null && !type.toString().isBlank())
                    ? ("[" + type + "] ") : "";
            sb.append("- ").append(tag).append(content).append("\n");
        }
        String result = sb.toString().trim();
        if (result.equals("## 用户偏好与历史洞察")) return "";
        log.debug("[AgenticRunner] Recalled {} user memories for prompt injection", memories.size());
        return result;
    }

    /**
     * Streaming handle for one agentic run.
     */
    public class AgentRunHandle {

        private final String threadId;
        private final AgentRunContext context;
        private final RunnableConfig runnableConfig;
        private final Flux<NodeOutput> flux;
        private final Flux<String> unifiedSseFlux;

        AgentRunHandle(String threadId, AgentRunContext context, RunnableConfig runnableConfig,
                       Flux<NodeOutput> flux, Flux<String> unifiedSseFlux) {
            this.threadId = threadId;
            this.context = context;
            this.runnableConfig = runnableConfig;
            this.flux = flux;
            this.unifiedSseFlux = unifiedSseFlux;
        }

        public String getThreadId() { return threadId; }
        public AgentRunContext getContext() { return context; }
        public RunnableConfig getRunnableConfig() { return runnableConfig; }
        public Flux<NodeOutput> getFlux() { return flux; }
        public Flux<String> getUnifiedSseFlux() { return unifiedSseFlux; }

        /**
         * After the stream completes, check whether the graph is paused before HITL.
         */
        public boolean isHaltedAtHitl() {
            try {
                StateSnapshot snapshot = compiledGraph.getState(runnableConfig);
                String next = snapshot == null ? null : snapshot.next();
                boolean halted = SqlAgentSpec.Node.HITL.equals(next);
                if (halted) {
                    context.setRunnableConfig(snapshot.config());
                    hitlSessionRegistry.register(context);
                    log.info("[AgenticRunner] Run halted at HITL: threadId={}", threadId);
                }
                return halted;
            } catch (Exception e) {
                log.warn("[AgenticRunner] getState failed for threadId={}: {}", threadId, e.getMessage());
                return false;
            }
        }
    }

    private int readInt(com.alibaba.cloud.ai.graph.OverAllState state, String key, int fallback) {
        Object value = state == null ? null : state.value(key, null);
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException e) { return fallback; }
    }
}
