package com.sql.logic.engine.trigger.http;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.application.service.AgentHistoryAppService;
import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.common.dto.SqlGenerateRequest;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.core.SqlAgentRunner;
import com.sql.logic.engine.domain.agent.core.SqlAgentRunner.AgentRunHandle;
import com.sql.logic.engine.domain.agent.service.SessionSummaryService;
import com.sql.logic.engine.domain.conversation.ConversationContextService;
import com.sql.logic.engine.domain.memory.MemoryExtractorService;
import com.sql.logic.engine.infrastructure.po.AgentExecution;

import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST controller for the SQL Agent streaming + HITL endpoints (Phase 4).
 * <p>
 * <ul>
 *   <li>{@code POST /api/v1/agent/sql/stream} — starts a run, streams per-node SSE events,
 *       and terminates with either {@code COMPLETED} (normal finish) or
 *       {@code AWAITING_CONFIRMATION} (paused at the HITL node).</li>
 *   <li>{@code POST /api/v1/agent/sql/continue} — injects the human decision on a paused
 *       run and resumes the SSE stream from the checkpoint.</li>
 * </ul>
 * <p>
 * Event format (each SSE data line is a JSON object):
 * <pre>
 * {"nodeName":"EVIDENCE_RECALL","outputType":"STARTED","messageType":"THINKING","sequenceNo":0}
 * {"nodeName":"EVIDENCE_RECALL","outputType":"FINISHED","data":{"rewriteQuery":"...","evidence":""}}
 * {"nodeName":"SQL_GENERATION","outputType":"FINISHED","data":{"sql":"..."}}
 * {"type":"AWAITING_CONFIRMATION","threadId":"...","plan":"...","repairCount":1}
 * {"type":"COMPLETED"}
 * {"type":"ERROR","message":"..."}
 * </pre>
 *
 * <p>node-output → SSE encoding (FINISHED) and the synthetic
 * STARTED events are owned by {@code AgentSseCodec} / {@code AgentSseStartedListener}
 * and surfaced by {@link AgentRunHandle#getUnifiedSseFlux()}. This controller only
 * attaches the terminal event, persists the execution history, and maps errors.
 */
@RestController
@RequestMapping("/api/v1/agent/sql")
public class SqlAgentController {

    private static final Logger log = LoggerFactory.getLogger(SqlAgentController.class);

    private final SqlAgentRunner sqlAgentRunner;
    private final UserAppService userAppService;
    private final ObjectMapper objectMapper;
    private final AgentHistoryAppService agentHistoryAppService;
    private final SessionSummaryService sessionSummaryService;
    private final MemoryExtractorService memoryExtractorService;
    private final ConversationContextService conversationContextService;

    public SqlAgentController(SqlAgentRunner sqlAgentRunner,
                            UserAppService userAppService,
                            ObjectMapper objectMapper,
                            AgentHistoryAppService agentHistoryAppService,
                            SessionSummaryService sessionSummaryService,
                            MemoryExtractorService memoryExtractorService,
                            ConversationContextService conversationContextService) {
        this.sqlAgentRunner = sqlAgentRunner;
        this.userAppService = userAppService;
        this.objectMapper = objectMapper;
        this.agentHistoryAppService = agentHistoryAppService;
        this.sessionSummaryService = sessionSummaryService;
        this.memoryExtractorService = memoryExtractorService;
        this.conversationContextService = conversationContextService;
    }

    /**
     * Start a run and stream per-node SSE events. After the upstream completes, inspect
     * the checkpoint: paused at HITL → emit {@code AWAITING_CONFIRMATION}; otherwise emit
     * {@code COMPLETED}.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamAgent(@RequestBody SqlGenerateRequest request) {
        Long currentUserId = currentUserId();
        if (currentUserId == null) {
            return Flux.error(new IllegalArgumentException("Invalid user ID in session"));
        }
        if (request.getUserId() != null && !request.getUserId().equals(currentUserId)) {
            return Flux.error(new IllegalArgumentException("UserId does not match logged-in user"));
        }

        try {
            userAppService.checkBeforeGeneration(currentUserId);
        } catch (Exception e) {
            return Flux.error(e);
        }

        boolean autoConfirm = request.getAutoConfirm() == null || request.getAutoConfirm();

        log.info("[SqlAgentController] Starting agent stream for userId={}, connectionId={}, autoConfirm={}, input='{}'",
                currentUserId, request.getConnectionId(), autoConfirm, request.getUserInput());

        // Phase B (B5): resolve the multi-turn conversation (create on first turn) and
        // load the windowed prior-turn history so the Agent can answer follow-ups.
        com.sql.logic.engine.infrastructure.po.Conversation conversation = conversationContextService.resolveConversation(
                request.getConversationId(), currentUserId, request.getUserInput(), request.getLlmConfigId());
        Long conversationId = conversation.getId();
        String historySection = conversationContextService.loadHistorySection(conversationId);

        AgentRunHandle handle = sqlAgentRunner.execute(
                request.getConnectionId(),
                request.getUserInput(),
                currentUserId,
                request.getLlmConfigId(),
                request.getWorkspaceId(),
                request.getTableNames(),
                request.getSchemaContext(),
                autoConfirm,
                conversationId,
                historySection
        );

        String threadId = handle.getThreadId();
        return handle.getUnifiedSseFlux()
                .concatWith(Flux.defer(() -> terminalEvent(handle, threadId, currentUserId, conversationId)))
                .doFinally(signalType -> {
                    if (signalType != SignalType.CANCEL) {
                        recordExecution(handle, request, currentUserId);
                    }
                })
                .onErrorResume(e -> {
                    log.error("[SqlAgentController] SSE stream error (threadId={})", threadId, e);
                    return Flux.just("{\"type\":\"ERROR\",\"message\":\"" + escape(e.getMessage()) + "\"}");
                });
    }

    /**
     * Resume a paused HITL session by injecting the human decision and streaming the
     * remainder of the graph execution.
     */
    @PostMapping(value = "/continue", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> continueAgent(@RequestBody SqlAgentConfirmRequest request) {
        Long currentUserId = currentUserId();
        if (currentUserId == null) {
            return Flux.error(new IllegalArgumentException("Invalid user ID in session"));
        }
        if (request.getThreadId() == null || request.getThreadId().isBlank()) {
            return Flux.error(new IllegalArgumentException("threadId is required"));
        }
        if (request.getApproved() == null) {
            return Flux.error(new IllegalArgumentException("approved is required"));
        }

        try {
            userAppService.checkBeforeGeneration(currentUserId);
        } catch (Exception e) {
            return Flux.error(e);
        }

        boolean approved = request.getApproved();
        String feedback = request.getFeedback() == null ? "" : request.getFeedback().trim();

        log.info("[SqlAgentController] Resuming agent threadId={}, userId={}, approved={}, feedbackLen={}",
                request.getThreadId(), currentUserId, approved, feedback.length());

        AgentRunHandle handle = sqlAgentRunner.resume(request.getThreadId(), approved, feedback, currentUserId);
        if (handle == null) {
            return Flux.just("{\"type\":\"ERROR\",\"message\":\"No pending confirmation session for this threadId (expired, consumed, or owned by another user).\"}");
        }

        String threadId = handle.getThreadId();
        Long conversationId = handle.getContext().getConversationId();
        return handle.getUnifiedSseFlux()
                .concatWith(Flux.defer(() -> terminalEvent(handle, threadId, currentUserId, conversationId)))
                .doFinally(signalType -> {
                    if (signalType != SignalType.CANCEL) {
                        recordExecution(handle, null, currentUserId);
                    }
                })
                .onErrorResume(e -> {
                    log.error("[SqlAgentController] SSE resume error (threadId={})", threadId, e);
                    return Flux.just("{\"type\":\"ERROR\",\"message\":\"" + escape(e.getMessage()) + "\"}");
                });
    }

    /**
     * Pick the terminal SSE event after the upstream completes: AWAITING_CONFIRMATION
     * if the run paused at HITL, else COMPLETED (carrying the conversationId so the
     * frontend can echo it on follow-up turns).
     */
    private Mono<String> terminalEvent(AgentRunHandle handle, String threadId, Long userId, Long conversationId) {
        return Mono.fromCallable(() -> {
            if (handle.isHaltedAtHitl()) {
                return awaitingConfirmationJson(handle, threadId);
            }
            return completedJson(conversationId);
        });
    }

    /** Resolve the authenticated user id, or null if the session is invalid. */
    private Long currentUserId() {
        String id = (String) StpUtil.getLoginId();
        if (id == null || !id.matches("\\d+")) {
            return null;
        }
        return Long.valueOf(id);
    }

    /**
     * Build the AWAITING_CONFIRMATION event carrying the plan, repair count and threadId
     * so the frontend can render the approval card and resume later.
     */
    private String awaitingConfirmationJson(AgentRunHandle handle, String threadId) {
        try {
            OverAllState state = null;
            try {
                state = handle.getRunnableConfig() == null ? null
                        : sqlAgentRunner.getCompiledGraph().getState(handle.getRunnableConfig()).state();
            } catch (Exception ignore) {
                // state snapshot may be unavailable; degrade to empty context
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "AWAITING_CONFIRMATION");
            event.put("threadId", threadId);
            String plan = state == null ? "" : state.value(SqlAgentSpec.StateKey.PLAN, "");
            event.put("plan", plan == null ? "" : plan);
            Object repair = state == null ? null : state.value(SqlAgentSpec.StateKey.REPAIR_COUNT, 1);
            event.put("repairCount", repair == null ? 1 : repair);
            Object needsReview = state == null ? Boolean.TRUE : state.value(SqlAgentSpec.StateKey.NEEDS_HUMAN_REVIEW, Boolean.TRUE);
            event.put("needsReview", needsReview == null ? true : needsReview);
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("[SqlAgentController] Failed to build AWAITING_CONFIRMATION event: {}", e.getMessage());
            return "{\"type\":\"AWAITING_CONFIRMATION\",\"threadId\":\"" + escape(threadId) + "\"}";
        }
    }

    private String completedJson(Long conversationId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "COMPLETED");
            if (conversationId != null) {
                body.put("conversationId", conversationId);
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            return "{\"type\":\"COMPLETED\"}";
        }
    }

    /** Escape a message fragment for safe inline JSON strings (best-effort). */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private void recordExecution(AgentRunHandle handle, SqlGenerateRequest request, Long userId) {
    try {
        // Pause at the HITL node: do not record yet — the resumed /continue call
        // records the consolidated history once the run truly finishes.
        if (handle.isHaltedAtHitl()) return;

        String userInput = request != null && request.getUserInput() != null
                ? request.getUserInput() : "";
        // For a resumed session there is no request object; recover the original
        // question from the graph state so the summary/title still reflects it.
        if (userInput.isBlank()) {
            userInput = readStateValue(handle, SqlAgentSpec.StateKey.INPUT, "");
        }

        AgentExecution exec = new AgentExecution();
        exec.setUserId(userId);
        exec.setInput(userInput);
        exec.setSummary(summariseSessionTitle(handle, userInput));
        exec.setStatus("COMPLETED");
        exec.setThreadId(handle.getThreadId());
        exec.setTotalDurationMs(0L);
        exec.setCreateTime(LocalDateTime.now());

        // Phase A3: populate trace summary fields from TraceContext (best-effort).
        com.sql.logic.engine.domain.trace.TraceContext tc = handle.getContext().getTraceContext();
        if (tc != null) {
            exec.setTotalTokens(tc.getTotalInputTokens() + tc.getTotalOutputTokens());
            exec.setModelCalls(tc.getModelCalls());
            exec.setToolCalls((int) tc.getSteps().values().stream()
                    .filter(st -> "TOOL_RESULT".equals(st.nodeType))
                    .count());
            exec.setTotalDurationMs(System.currentTimeMillis() - tc.getStartTime());
        }

        if (request != null) {
            exec.setConnectionId(request.getConnectionId());
            exec.setSchemaName(request.getSchemaContext());
            exec.setWorkspaceId(request.getWorkspaceId());
        } else {
            var ctx = handle.getContext();
            exec.setConnectionId(ctx.getConnectionId());
            exec.setSchemaName(ctx.getSchemaName());
            exec.setWorkspaceId(ctx.getWorkspaceId());
        }

        agentHistoryAppService.saveExecution(exec);
        log.info("[SqlAgentController] Recorded agent execution id={}, summary='{}'",
                exec.getId(), exec.getSummary());

        // Phase B memory extraction (auto trigger #1): after a run truly finishes
        // (HITL-paused runs return early above), asynchronously extract long-term
        // memories from this session. Non-blocking + best-effort — never affects the
        // response. HITL-approved completions flow through here too (their /continue
        // call reaches recordExecution once the graph ends), covering trigger #2.
        triggerMemoryExtraction(handle, userId, userInput);

        // Phase B (B5): persist this turn into the conversation so the next follow-up
        // can recall it as context (sliding window managed by ConversationContextService).
        Long conversationId = handle.getContext().getConversationId();
        if (conversationId != null) {
            String report = readStateValue(handle, SqlAgentSpec.StateKey.REPORT_RESULT, "");
            String sql = readStateValue(handle, SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, "");
            String execResult = readStateValue(handle, SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, "");
            conversationContextService.appendTurn(conversationId, userInput, sql,
                    report != null && !report.isBlank() ? report : execResult);
        }

        // Persist the buffered per-node steps so the history timeline can be replayed.
        // Steps captured before the HITL pause PLUS resumed steps share the same context,
        // so draining yields the full session. Failures here never block the response.
        try {
            java.util.List<com.sql.logic.engine.infrastructure.po.AgentExecutionStep> steps =
                    handle.getContext().drainSteps();
            if (steps != null && !steps.isEmpty()) {
                // Phase A3: enrich each step with trace data (latency/tokens/nodeType) where available.
                com.sql.logic.engine.domain.trace.TraceContext stepTc = handle.getContext().getTraceContext();
                LocalDateTime now = LocalDateTime.now();
                for (com.sql.logic.engine.infrastructure.po.AgentExecutionStep s : steps) {
                    s.setExecutionId(exec.getId());
                    if (s.getCreateTime() == null) s.setCreateTime(now);
                    if (stepTc != null) {
                        try {
                            // key is now nodeName (TraceContext per-step map keyed by nodeName)
                            com.sql.logic.engine.domain.trace.TraceContext.StepTrace st =
                                    stepTc.getSteps().get(s.getNodeName());
                            if (st != null) {
                                s.setInputTokens(st.inputTokens);
                                s.setOutputTokens(st.outputTokens);
                                s.setLatencyMs(st.latencyMs);
                                // real per-node execution duration (begin→end).
                                if (st.durationMs > 0) {
                                    s.setDurationMs(st.durationMs);
                                }
                                if (s.getNodeType() == null) s.setNodeType(st.nodeType);
                            }
                        } catch (Exception ignore) { /* best-effort enrichment */ }
                    }
                }
                agentHistoryAppService.saveSteps(steps);
                log.info("[SqlAgentController] Persisted {} step records for execution id={}",
                        steps.size(), exec.getId());
            }
        } catch (Exception stepEx) {
            log.warn("[SqlAgentController] Failed to persist step history: {}", stepEx.getMessage());
        }
    } catch (Exception e) {
        log.warn("[SqlAgentController] Failed to record execution history: {}", e.getMessage());
    }
}

    /**
     * Phase B memory extraction: hand the finished session (user input + report/SQL
     * transcript) to the async {@link MemoryExtractorService}. Best-effort and fully
     * non-blocking — failures are logged inside the service and never propagate.
     */
    private void triggerMemoryExtraction(AgentRunHandle handle, Long userId, String userInput) {
        try {
            String report = readStateValue(handle, SqlAgentSpec.StateKey.REPORT_RESULT, "");
            String sql = readStateValue(handle, SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, "");
            StringBuilder transcript = new StringBuilder();
            if (report != null && !report.isBlank()) transcript.append(report.trim());
            if (sql != null && !sql.isBlank()) {
                if (transcript.length() > 0) transcript.append("\n\n");
                transcript.append("生成SQL:\n").append(sql.trim());
            }
            Long workspaceId = handle.getContext().getWorkspaceId();
            memoryExtractorService.extractAndPersistAsync(
                    userId, workspaceId, handle.getThreadId(), userInput, transcript.toString());
        } catch (Exception e) {
            log.debug("[SqlAgentController] Memory extraction trigger skipped: {}", e.getMessage());
        }
    }

    /**
     * Build a compact conversation excerpt (final report + generated SQL) from the
     * finished graph state, then ask the {@link SessionSummaryService} for a short
     * title. Best-effort: degrades to a truncated user input on any failure.
     */
    private String summariseSessionTitle(AgentRunHandle handle, String userInput) {
        String report = readStateValue(handle, SqlAgentSpec.StateKey.REPORT_RESULT, "");
        String sql = readStateValue(handle, SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, "");
        StringBuilder transcript = new StringBuilder();
        if (report != null && !report.isBlank()) {
            transcript.append(report.trim());
        }
        if (sql != null && !sql.isBlank()) {
            if (transcript.length() > 0) transcript.append("\n\n");
            transcript.append("生成SQL:\n").append(sql.trim());
        }
        Long llmConfigId = readLongState(handle, SqlAgentSpec.StateKey.LLM_CONFIG_ID);
        return sessionSummaryService.summarise(userInput, transcript.toString(), llmConfigId, handle.getContext().getUserId());
    }

    /** Best-effort read of a String state value from the finished run's snapshot. */
    private String readStateValue(AgentRunHandle handle, String key, String def) {
        try {
            if (handle.getRunnableConfig() == null) return def;
            var snapshot = sqlAgentRunner.getCompiledGraph().getState(handle.getRunnableConfig());
            var state = snapshot == null ? null : snapshot.state();
            if (state == null) return def;
            Object v = state.value(key, def);
            return v == null ? def : String.valueOf(v);
        } catch (Exception e) {
            log.debug("[SqlAgentController] readStateValue('{}') failed: {}", key, e.getMessage());
            return def;
        }
    }

    /** Best-effort read of a Long state value from the finished run's snapshot. */
    private Long readLongState(AgentRunHandle handle, String key) {
        try {
            if (handle.getRunnableConfig() == null) return null;
            var snapshot = sqlAgentRunner.getCompiledGraph().getState(handle.getRunnableConfig());
            var state = snapshot == null ? null : snapshot.state();
            if (state == null) return null;
            Object v = state.value(key, null);
            return com.sql.logic.engine.domain.agent.AgentStateUtil.toLong(v);
        } catch (Exception e) {
            return null;
        }
    }
}