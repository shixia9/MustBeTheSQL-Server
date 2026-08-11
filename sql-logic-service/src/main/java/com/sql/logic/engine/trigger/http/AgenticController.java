package com.sql.logic.engine.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.application.service.AgentHistoryAppService;
import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.common.dto.SqlGenerateRequest;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.core.AgenticRunner;
import com.sql.logic.engine.domain.agent.core.AgenticRunner.AgentRunHandle;
import com.sql.logic.engine.domain.agent.core.AgentEventSinkRegistry;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.service.SessionSummaryService;
import com.sql.logic.engine.domain.agentic.context.ContextBudgetConfig;
import com.sql.logic.engine.domain.agentic.context.ContextManager;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import com.sql.logic.engine.domain.conversation.ConversationContextService;
import com.sql.logic.engine.domain.memory.MemoryExtractorService;
import com.sql.logic.engine.infrastructure.po.AgentExecution;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;

import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the 6-Agent Multi-Agent streaming endpoint (Phase 2-3).
 * <p>
 * <ul>
 *   <li>{@code POST /api/v1/agentic/stream} — starts a 6-Agent run, streams per-agent SSE events</li>
 *   <li>{@code POST /api/v1/agentic/continue} — resumes a HITL-paused run</li>
 * </ul>
 * <p>
 * Event format (SSE): same as {@link SqlAgentController} with 6 node names:
 * <pre>
 * {"nodeName":"MANAGER","outputType":"STARTED","messageType":"STATUS","sequenceNo":0}
 * {"nodeName":"MANAGER","outputType":"FINISHED","data":{"nextNode":"PLANNER",...}}
 * {"nodeName":"DATA_SCIENTIST","outputType":"FINISHED","data":{"sql":"...","sqlExecutionResult":"..."}}
 * {"type":"COMPLETED"}
 * {"type":"ERROR","message":"..."}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/agentic")
public class AgenticController {

    private static final Logger log = LoggerFactory.getLogger(AgenticController.class);

    private final AgenticRunner agenticRunner;
    private final UserAppService userAppService;
    private final ObjectMapper objectMapper;
    private final AgentHistoryAppService agentHistoryAppService;
    private final SessionSummaryService sessionSummaryService;
    private final MemoryExtractorService memoryExtractorService;
    private final ConversationContextService conversationContextService;
    private final ContextBudgetConfig contextBudgetConfig;
    private final LlmClientManager llmClientManager;
    private final AgentEventSinkRegistry agentEventSinkRegistry;

    public AgenticController(AgenticRunner agenticRunner,
                             UserAppService userAppService,
                             ObjectMapper objectMapper,
                             AgentHistoryAppService agentHistoryAppService,
                             SessionSummaryService sessionSummaryService,
                             MemoryExtractorService memoryExtractorService,
                             ConversationContextService conversationContextService,
                             ContextBudgetConfig contextBudgetConfig,
                             LlmClientManager llmClientManager,
                             AgentEventSinkRegistry agentEventSinkRegistry) {
        this.agenticRunner = agenticRunner;
        this.userAppService = userAppService;
        this.objectMapper = objectMapper;
        this.agentHistoryAppService = agentHistoryAppService;
        this.sessionSummaryService = sessionSummaryService;
        this.memoryExtractorService = memoryExtractorService;
        this.conversationContextService = conversationContextService;
        this.contextBudgetConfig = contextBudgetConfig;
        this.llmClientManager = llmClientManager;
        this.agentEventSinkRegistry = agentEventSinkRegistry;
    }

    /**
     * Start a 6-Agent Multi-Agent run and stream per-agent SSE events.
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

        log.info("[AgenticController] Starting 6-Agent stream for userId={}, connectionId={}, input='{}'",
                currentUserId, request.getConnectionId(), request.getUserInput());

        // Resolve multi-turn conversation
        com.sql.logic.engine.infrastructure.po.Conversation conversation = conversationContextService.resolveConversation(
                request.getConversationId(), currentUserId, request.getUserInput(), request.getLlmConfigId());
        Long conversationId = conversation.getId();
        String historySection = conversationContextService.loadHistorySection(conversationId);

        AgentRunHandle handle = agenticRunner.execute(
                request.getConnectionId(),
                request.getUserInput(),
                currentUserId,
                request.getLlmConfigId(),
                request.getWorkspaceId(),
                request.getTableNames(),
                request.getSchemaContext(),
                autoConfirm,
                conversationId,
                historySection,
                request.getHtmlReport() != null && request.getHtmlReport(),
                request.getToolInvocation()
        );

        String threadId = handle.getThreadId();
        return handle.getUnifiedSseFlux()
                .concatWith(Flux.defer(() -> terminalEvent(handle, threadId, conversationId)))
                .doFinally(signalType -> {
                    if (signalType != SignalType.CANCEL) {
                        recordExecution(handle, request, currentUserId);
                        triggerMemoryExtractionSafe(handle, currentUserId);
                    }
                })
                .onErrorResume(e -> {
                    log.error("[AgenticController] SSE stream error (threadId={})", threadId, e);
                    return Flux.just("{\"type\":\"ERROR\",\"message\":\"" + escape(e.getMessage()) + "\"}");
                });
    }

    /**
     * Resume a HITL-paused 6-Agent run.
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

        log.info("[AgenticController] Resuming agentic threadId={}, userId={}, approved={}",
                request.getThreadId(), currentUserId, approved);

        AgentRunHandle handle = agenticRunner.resume(request.getThreadId(), approved, feedback, currentUserId);
        if (handle == null) {
            return Flux.just("{\"type\":\"ERROR\",\"message\":\"No pending confirmation session for this threadId.\"}");
        }

        String threadId = handle.getThreadId();
        Long conversationId = handle.getContext().getConversationId();
        return handle.getUnifiedSseFlux()
                .concatWith(Flux.defer(() -> terminalEvent(handle, threadId, conversationId)))
                .doFinally(signalType -> {
                    if (signalType != SignalType.CANCEL) {
                        recordExecution(handle, null, currentUserId);
                        triggerMemoryExtractionSafe(handle, currentUserId);
                    }
                })
                .onErrorResume(e -> {
                    log.error("[AgenticController] SSE resume error (threadId={})", threadId, e);
                    return Flux.just("{\"type\":\"ERROR\",\"message\":\"" + escape(e.getMessage()) + "\"}");
                });
    }

    // ======================== Context Compaction (manual) ========================

    /**
     * Report the current context budget usage for a conversation.
     * <p>
     * Reconstructs the persisted turn records into {@link AgentMessage}s and
     * counts real tokens against {@link ContextBudgetConfig#effectiveBudget()}.
     * Used by the frontend circular progress ring to show how full the context
     * window is.
     */
    @GetMapping("/context/budget")
    public Map<String, Object> contextBudget(@RequestParam(required = false) Long conversationId) {
        int budget = contextBudgetConfig.effectiveBudget();
        List<AgentMessage> messages = reconstructMessages(conversationId);
        int used = countTokensSafe(messages);
        long pct = budget > 0 ? Math.round(used * 100.0 / budget) : 0;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("budget", budget);
        m.put("usedTokens", used);
        m.put("usagePercent", Math.min(pct, 100));
        m.put("turnCount", messages.size());
        return m;
    }

    /**
     * Manually trigger progressive context compaction on a conversation's
     * persisted history.
     * <p>
     * A fresh, isolated {@link ContextManager} is constructed per request so the
     * singleton bean's tracker / circuit-breaker state is never polluted by
     * manual operations. When a live SSE sink exists for the supplied
     * {@code threadId}, each applied compaction layer streams a
     * {@code CONTEXT_COMPACT} event to the active session (best-effort, no-op
     * when the stream has already completed).
     *
     * @param body {@code {"conversationId": Long, "threadId": String?}}
     */
    @PostMapping("/context/compact")
    public Map<String, Object> compactContext(@RequestBody Map<String, Object> body) {
        Long conversationId = toLong(body.get("conversationId"));
        String threadId = body.get("threadId") == null ? null : String.valueOf(body.get("threadId"));

        List<AgentMessage> messages = reconstructMessages(conversationId);
        int before = countTokensSafe(messages);

        // Isolated ContextManager — fresh tracker, does not touch the shared bean.
        ContextManager local = new ContextManager(contextBudgetConfig, llmClientManager, agentEventSinkRegistry);
        List<AgentMessage> compacted = local.manageContext(messages, 1, "", threadId);
        int after = countTokensSafe(compacted);

        int budget = contextBudgetConfig.effectiveBudget();
        long pctBefore = budget > 0 ? Math.round(before * 100.0 / budget) : 0;
        long pctAfter = budget > 0 ? Math.round(after * 100.0 / budget) : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("budget", budget);
        m.put("tokensBefore", before);
        m.put("tokensAfter", after);
        m.put("usagePercentBefore", Math.min(pctBefore, 100));
        m.put("usagePercentAfter", Math.min(pctAfter, 100));
        m.put("applied", after < before);
        m.put("reduced", Math.max(0, before - after));
        return m;
    }

    /**
     * Reconstruct a list of {@link AgentMessage}s from the persisted conversation
     * turns (oldest-first). Each turn yields a USER message (the question) and an
     * AI message (the SQL + execution/report result), mirroring the data captured
     * by {@link ConversationContextService#appendTurn}.
     */
    private List<AgentMessage> reconstructMessages(Long conversationId) {
        if (conversationId == null) return List.of();
        List<ConversationDetail> details = conversationContextService.loadDetails(conversationId);
        if (details.isEmpty()) return List.of();
        List<AgentMessage> messages = new ArrayList<>(details.size() * 2);
        for (ConversationDetail d : details) {
            if (d.getUserInput() != null && !d.getUserInput().isBlank()) {
                messages.add(AgentMessage.user(d.getUserInput()));
            }
            StringBuilder ai = new StringBuilder();
            if (d.getSqlOutput() != null && !d.getSqlOutput().isBlank()) {
                ai.append("SQL:\n").append(d.getSqlOutput()).append("\n\n");
            }
            if (d.getExecuteResult() != null && !d.getExecuteResult().isBlank()) {
                ai.append("结果:\n").append(d.getExecuteResult());
            }
            if (ai.length() > 0) {
                messages.add(AgentMessage.ai(ai.toString()));
            }
        }
        return messages;
    }

    /** Count tokens via an isolated tracker; never throws. */
    private int countTokensSafe(List<AgentMessage> messages) {
        try {
            return new ContextManager(contextBudgetConfig).getTracker().countMessages(messages);
        } catch (Exception e) {
            log.warn("[AgenticController] countTokensSafe failed: {}", e.getMessage());
            return 0;
        }
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.valueOf(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private Mono<String> terminalEvent(AgentRunHandle handle, String threadId, Long conversationId) {
        return Mono.fromCallable(() -> {
            if (handle.isHaltedAtHitl()) {
                return awaitingConfirmationJson(handle, threadId);
            }
            return completedJson(conversationId);
        });
    }

    private Long currentUserId() {
        String id = (String) StpUtil.getLoginId();
        if (id == null || !id.matches("\\d+")) return null;
        return Long.valueOf(id);
    }

    private String awaitingConfirmationJson(AgentRunHandle handle, String threadId) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "AWAITING_CONFIRMATION");
            event.put("threadId", threadId);
            event.put("plan", "");
            event.put("repairCount", 1);
            event.put("needsReview", true);
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
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

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private void recordExecution(AgentRunHandle handle, SqlGenerateRequest request, Long userId) {
        try {
            if (handle.isHaltedAtHitl()) return;

            String userInput = request != null && request.getUserInput() != null
                    ? request.getUserInput() : "";
            if (userInput.isBlank()) {
                userInput = readStateValue(handle, SqlAgentSpec.StateKey.INPUT, "");
            }

            AgentExecution exec = new AgentExecution();
            exec.setUserId(userId);
            exec.setInput(userInput);
            exec.setSummary(summariseSessionTitle(handle, userInput));
            exec.setStatus("COMPLETED");
            exec.setThreadId(handle.getThreadId());
            exec.setConversationId(handle.getContext().getConversationId());
            exec.setAgentId(readLongState(handle, SqlAgentSpec.StateKey.AGENT_ID));
            exec.setTotalDurationMs(0L);
            exec.setCreateTime(LocalDateTime.now());

            com.sql.logic.engine.domain.trace.TraceContext tc = handle.getContext().getTraceContext();
            if (tc != null) {
                exec.setTotalTokens(tc.getTotalInputTokens() + tc.getTotalOutputTokens());
                exec.setModelCalls(tc.getModelCalls());
                exec.setToolCalls((int) tc.getSteps().stream()
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
            log.info("[AgenticController] Recorded agentic execution id={}, summary='{}'",
                    exec.getId(), exec.getSummary());

            Long conversationId = handle.getContext().getConversationId();
            if (conversationId != null) {
                String report = readStateValue(handle, SqlAgentSpec.StateKey.REPORT_RESULT, "");
                String sql = readStateValue(handle, SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, "");
                String execResult = readStateValue(handle, SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, "");
                conversationContextService.appendTurn(conversationId, userInput, sql,
                        report != null && !report.isBlank() ? report : execResult);
                if (exec.getSummary() != null && !exec.getSummary().isBlank()) {
                    conversationContextService.updateTitle(conversationId, exec.getSummary());
                }
            }

            try {
                java.util.List<com.sql.logic.engine.infrastructure.po.AgentExecutionStep> steps =
                        handle.getContext().drainSteps();
                if (steps != null && !steps.isEmpty()) {
                    LocalDateTime now = LocalDateTime.now();
                    for (var s : steps) {
                        s.setExecutionId(exec.getId());
                        if (s.getCreateTime() == null) s.setCreateTime(now);
                    }
                    agentHistoryAppService.saveSteps(steps);
                    log.info("[AgenticController] Persisted {} step records for execution id={}",
                            steps.size(), exec.getId());
                }
            } catch (Exception stepEx) {
                log.warn("[AgenticController] Failed to persist step history: {}", stepEx.getMessage());
            }
        } catch (Exception e) {
            log.warn("[AgenticController] Failed to record execution history: {}", e.getMessage());
        }
    }

    private void triggerMemoryExtractionSafe(AgentRunHandle handle, Long userId) {
        try {
            if (handle.isHaltedAtHitl()) return;
            String userInput = readStateValue(handle, SqlAgentSpec.StateKey.INPUT, "");
            if (userInput.isBlank()) return;
            Long workspaceId = handle.getContext().getWorkspaceId();
            Long llmConfigId = handle.getContext().getLlmConfigId();
            Long agentId = readLongState(handle, SqlAgentSpec.StateKey.AGENT_ID);
            String report = readStateValue(handle, SqlAgentSpec.StateKey.REPORT_RESULT, "");
            String sessionSummary = (report != null && !report.isBlank()) ? report : userInput;
            memoryExtractorService.extractAndPersistAsync(
                    userId, workspaceId, agentId, handle.getThreadId(), userInput, sessionSummary, llmConfigId);
        } catch (Exception e) {
            log.debug("[AgenticController] Memory extraction safe trigger skipped: {}", e.getMessage());
        }
    }

    private String summariseSessionTitle(AgentRunHandle handle, String userInput) {
        String report = readStateValue(handle, SqlAgentSpec.StateKey.REPORT_RESULT, "");
        String sql = readStateValue(handle, SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, "");
        StringBuilder transcript = new StringBuilder();
        if (report != null && !report.isBlank()) transcript.append(report.trim());
        if (sql != null && !sql.isBlank()) {
            if (transcript.length() > 0) transcript.append("\n\n");
            transcript.append("生成SQL:\n").append(sql.trim());
        }
        Long llmConfigId = readLongState(handle, SqlAgentSpec.StateKey.LLM_CONFIG_ID);
        return sessionSummaryService.summarise(userInput, transcript.toString(), llmConfigId, handle.getContext().getUserId());
    }

    private String readStateValue(AgentRunHandle handle, String key, String def) {
        try {
            if (handle.getRunnableConfig() == null) return def;
            var snapshot = agenticRunner.getCompiledGraph().getState(handle.getRunnableConfig());
            var state = snapshot == null ? null : snapshot.state();
            if (state == null) return def;
            Object v = state.value(key, def);
            return v == null ? def : String.valueOf(v);
        } catch (Exception e) {
            return def;
        }
    }

    private Long readLongState(AgentRunHandle handle, String key) {
        try {
            if (handle.getRunnableConfig() == null) return null;
            var snapshot = agenticRunner.getCompiledGraph().getState(handle.getRunnableConfig());
            var state = snapshot == null ? null : snapshot.state();
            if (state == null) return null;
            Object v = state.value(key, null);
            return AgentStateUtil.toLong(v);
        } catch (Exception e) {
            return null;
        }
    }
}
