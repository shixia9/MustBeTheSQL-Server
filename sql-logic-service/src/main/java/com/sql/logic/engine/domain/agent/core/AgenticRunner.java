package com.sql.logic.engine.domain.agent.core;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.sql.logic.engine.application.service.DatabaseMetaDataService;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agentic.agent.ManagerAgent;
import com.sql.logic.engine.domain.agentic.config.AgentOrchestrator;
import com.sql.logic.engine.domain.agentic.enrichment.SchemaEnrichmentService;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Streaming executor for the 6-Agent Multi-Agent system (AgentOrchestrator).
 */
@Service
public class AgenticRunner {

    private static final Logger log = LoggerFactory.getLogger(AgenticRunner.class);

    private final CompiledGraph compiledGraph;
    private final AgentOrchestrator orchestrator;
    private final ManagerAgent managerAgent;
    private final HitlSessionRegistry hitlSessionRegistry;
    private final TraceContextRegistry traceContextRegistry;
    private final NodeStartedSinkRegistry sinkRegistry;
    private final AgentEventSinkRegistry eventSinkRegistry;
    private final AgentSseCodec codec;
    private final DatabaseMetaDataService databaseMetaDataService;
    private final SchemaEnrichmentService schemaEnrichmentService;
    private final ExecutorService schemaLinkingExecutor;

    public AgenticRunner(AgentOrchestrator orchestrator,
                         ManagerAgent managerAgent,
                         HitlSessionRegistry hitlSessionRegistry,
                         TraceContextRegistry traceContextRegistry,
                         AgentSseStartedListener sseStartedListener,
                         NodeStartedSinkRegistry sinkRegistry,
                         AgentEventSinkRegistry eventSinkRegistry,
                         AgentSseCodec codec,
                         DatabaseMetaDataService databaseMetaDataService,
                         SchemaEnrichmentService schemaEnrichmentService,
                         ExecutorService schemaLinkingExecutor) {
        this.orchestrator = orchestrator;
        this.managerAgent = managerAgent;
        this.hitlSessionRegistry = hitlSessionRegistry;
        this.traceContextRegistry = traceContextRegistry;
        this.sinkRegistry = sinkRegistry;
        this.eventSinkRegistry = eventSinkRegistry;
        this.codec = codec;
        this.databaseMetaDataService = databaseMetaDataService;
        this.schemaEnrichmentService = schemaEnrichmentService;
        this.schemaLinkingExecutor = schemaLinkingExecutor;
        try {
            this.compiledGraph = orchestrator.compile(CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(new MemorySaver()).build())
                    .withLifecycleListener(sseStartedListener)
                    .build());
            log.info("[AgenticRunner] Agentic graph compiled with lifecycle listener. "
                    + "6-Agent chain: START → MANAGER → {PLANNER, DATA_SCIENTIST, CODE_ASSISTANT, TOOL_ASSISTANT, DASHBOARD} → END");
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
        String threadId = UUID.randomUUID().toString();
        RunnableConfig rc = RunnableConfig.builder().threadId(threadId).build();

        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put(SqlAgentSpec.StateKey.INPUT, userInput);
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
        initialState.put(SqlAgentSpec.StateKey.REPAIR_COUNT, 1);
        initialState.put("htmlReport", htmlReport);

        // Quick fallback schema (fast, no LLM) — available immediately for ManagerAgent routing.
        // The enriched (LLM-filtered) version is built on a background thread and becomes
        // available to downstream agents (DataScientist, etc.) when ready.
        String quickSchema = schemaEnrichmentService.buildQuickFallback(connectionId, tableNames, schemaName);
        initialState.put(SqlAgentSpec.StateKey.SCHEMA_DDL, quickSchema);

        // Resolve actual database dialect type from connection config
        String dbType = resolveDbType(connectionId);
        initialState.put(SqlAgentSpec.StateKey.DB_TYPE, dbType);

        log.info("[AgenticRunner] Starting 6-Agent graph: threadId={}, autoConfirm={}, connectionId={}, userId={}, llmConfigId={}, input='{}'",
                threadId, autoConfirm, connectionId, userId, llmConfigId, userInput);

        TraceContext traceContext = new TraceContext(threadId, userId, workspaceId);
        traceContextRegistry.register(threadId, traceContext);
        initialState.put(SqlAgentSpec.StateKey.TRACE_CONTEXT, traceContext);

        // Launch async schema enrichment (LLM semantic filtering) in the background.
        // The quick fallback SCHEMA_DDL is already in state — downstream agents will
        // transparently pick up the enriched version when it completes.
        String convHistory = (conversationHistory != null) ? conversationHistory : "";
        CompletableFuture<String> enrichmentFuture = CompletableFuture.supplyAsync(
                () -> schemaEnrichmentService.enrich(connectionId, tableNames, schemaName,
                        userInput, convHistory, "",
                        llmConfigId, userId, traceContext),
                schemaLinkingExecutor);
        initialState.put("_schemaEnrichmentFuture", enrichmentFuture);

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
     * Resume a HITL-paused session by notifying the ManagerAgent.
     */
    public AgentRunHandle resume(String threadId, boolean approved, String feedback, Long userId) {
        AgentRunContext context = hitlSessionRegistry.get(threadId).orElse(null);
        if (context == null) {
            log.warn("[AgenticRunner] resume: no pending session for threadId={}", threadId);
            return null;
        }
        if (userId != null && !userId.equals(context.getUserId())) {
            log.warn("[AgenticRunner] resume: userId {} does not own threadId={}", userId, threadId);
            return null;
        }

        // Signal the ManagerAgent to continue
        managerAgent.resumeWithDecision(approved);
        log.info("[AgenticRunner] Resumed threadId={} approved={}", threadId, approved);

        RunnableConfig rc = context.getRunnableConfig();
        Sinks.Many<String> startedSink = sinkRegistry.register(threadId);
        Sinks.Many<String> customSink = eventSinkRegistry.register(threadId);
        Flux<NodeOutput> graphFlux = compiledGraph.stream(null, rc)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(output -> log.info("[AgenticRunner] Resumed agent node completed (threadId={}): {}", threadId, output.node()))
                .doOnComplete(() -> {
                    log.info("[AgenticRunner] Resume stream complete (threadId={})", threadId);
                    hitlSessionRegistry.remove(threadId);
                })
                .doOnError(e -> log.error("[AgenticRunner] Resume stream error (threadId={})", threadId, e));

        Flux<String> unified = buildUnifiedSse(graphFlux, startedSink, customSink, threadId, context);
        return new AgentRunHandle(threadId, context, rc, graphFlux, unified);
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

    public ManagerAgent getManagerAgent() {
        return managerAgent;
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
         * After the stream completes, check if the ManagerAgent is waiting for HITL.
         */
        public boolean isHaltedAtHitl() {
            String pending = managerAgent.getPendingThreadId();
            boolean halted = pending != null && pending.equals(threadId);
            if (halted) {
                hitlSessionRegistry.register(context);
                log.info("[AgenticRunner] Run halted at HITL: threadId={}", threadId);
            }
            return halted;
        }
    }
}
