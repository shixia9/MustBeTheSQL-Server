package com.sql.logic.engine.trigger.http;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.common.dto.SqlGenerateRequest;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.core.SqlAgentRunner;
import com.sql.logic.engine.domain.agent.core.SqlAgentRunner.AgentRunHandle;

import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
 * {"nodeName":"EVIDENCE_RECALL","outputType":"FINISHED","data":{"rewriteQuery":"...","evidence":""}}
 * {"nodeName":"SQL_GENERATION","outputType":"FINISHED","data":{"sql":"..."}}
 * {"type":"AWAITING_CONFIRMATION","threadId":"...","plan":"...","repairCount":1}
 * {"type":"COMPLETED"}
 * {"type":"ERROR","message":"..."}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/agent/sql")
public class SqlAgentController {

    private static final Logger log = LoggerFactory.getLogger(SqlAgentController.class);

    private final SqlAgentRunner sqlAgentRunner;
    private final UserAppService userAppService;
    private final ObjectMapper objectMapper;

    public SqlAgentController(SqlAgentRunner sqlAgentRunner,
                              UserAppService userAppService,
                              ObjectMapper objectMapper) {
        this.sqlAgentRunner = sqlAgentRunner;
        this.userAppService = userAppService;
        this.objectMapper = objectMapper;
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

        AgentRunHandle handle = sqlAgentRunner.execute(
                request.getConnectionId(),
                request.getUserInput(),
                currentUserId,
                request.getLlmConfigId(),
                request.getTableNames(),
                autoConfirm
        );

        String threadId = handle.getThreadId();
        return handle.getFlux()
                .map(this::nodeOutputToJson)
                .filter(json -> !json.isEmpty())  // Skip empty (START/END pseudo-nodes)
                .concatWith(Flux.defer(() -> terminalEvent(handle, threadId, currentUserId)))
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
        return handle.getFlux()
                .map(this::nodeOutputToJson)
                .filter(json -> !json.isEmpty())
                .concatWith(Flux.defer(() -> terminalEvent(handle, threadId, currentUserId)))
                .onErrorResume(e -> {
                    log.error("[SqlAgentController] SSE resume error (threadId={})", threadId, e);
                    return Flux.just("{\"type\":\"ERROR\",\"message\":\"" + escape(e.getMessage()) + "\"}");
                });
    }

    /**
     * Pick the terminal SSE event after the upstream completes: AWAITING_CONFIRMATION
     * if the run paused at HITL, else COMPLETED.
     */
    private Mono<String> terminalEvent(AgentRunHandle handle, String threadId, Long userId) {
        return Mono.fromCallable(() -> {
            if (handle.isHaltedAtHitl()) {
                return awaitingConfirmationJson(handle, threadId);
            }
            return completedJson();
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

    private String completedJson() {
        try {
            return objectMapper.writeValueAsString(Map.of("type", "COMPLETED"));
        } catch (Exception e) {
            return "{\"type\":\"COMPLETED\"}";
        }
    }

    /**
     * Convert a NodeOutput to an SSE JSON string. Skips START/END pseudo-nodes, extracts
     * the node-relevant (non-sensitive) state into {@code data}.
     */
    private String nodeOutputToJson(NodeOutput output) {
        try {
            String nodeName = output.node();

            if ("__start__".equalsIgnoreCase(nodeName) || "__end__".equalsIgnoreCase(nodeName)) {
                return "";
            }

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("nodeName", nodeName);
            event.put("outputType", "FINISHED");

            OverAllState state = output.state();
            if (state != null) {
                event.put("data", extractNodeData(nodeName, state));
            }
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("[SqlAgentController] Failed to serialize NodeOutput", e);
            return "{\"nodeName\":\"ERROR\",\"outputType\":\"ERROR\",\"message\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "connectionId", "llmConfigId", "userId"
    );

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractNodeData(String nodeName, OverAllState state) {
        Map<String, Object> data = new LinkedHashMap<>();
        Integer currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP);

        switch (nodeName) {
            case SqlAgentSpec.Node.EVIDENCE_RECALL:
                data.put("rewriteQuery", state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, ""));
                data.put("evidence", state.value(SqlAgentSpec.StateKey.EVIDENCE, ""));
                break;
            case SqlAgentSpec.Node.SCHEMA_LINKING:
                data.put("tableRelation", state.value(SqlAgentSpec.StateKey.TABLE_RELATION, ""));
                data.put("filteredTables", extractFilteredTableNames(state));
                break;
            case SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT:
                data.put("feasibilityResult", state.value(SqlAgentSpec.StateKey.FEASIBILITY_RESULT, ""));
                break;
            case SqlAgentSpec.Node.PLANNER:
                data.put("plan", state.value(SqlAgentSpec.StateKey.PLAN, ""));
                break;
            case SqlAgentSpec.Node.HITL_GATE: {
                Object v = state.value(SqlAgentSpec.StateKey.NEEDS_HUMAN_REVIEW, Boolean.FALSE);
                boolean needsReview = v instanceof Boolean ? (Boolean) v
                        : (v != null && Boolean.parseBoolean(String.valueOf(v)));
                data.put("needsReview", needsReview);
                data.put("reason", state.value("hitlGateReason", ""));
                data.put("repairCount", readInt(state, SqlAgentSpec.StateKey.REPAIR_COUNT));
                break;
            }
            case SqlAgentSpec.Node.HITL: {
                // Paused here pre-node — emit the awaiting-confirmation payload too.
                data.put("needsReview", true);
                data.put("awaitingConfirmation", true);
                data.put("plan", state.value(SqlAgentSpec.StateKey.PLAN, ""));
                data.put("repairCount", readInt(state, SqlAgentSpec.StateKey.REPAIR_COUNT));
                break;
            }
            case SqlAgentSpec.Node.PLAN_DISPATCH:
                data.put("currentStep", currentStep);
                data.put("nextNode", state.value(SqlAgentSpec.StateKey.NEXT_NODE, ""));
                break;
            case SqlAgentSpec.Node.SQL_GENERATION:
                data.put("sql", state.value(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, ""));
                data.put("step", currentStep);
                data.put("fixAttemptCount", readInt(state, SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT));
                break;
            case SqlAgentSpec.Node.SQL_EXECUTION:
                data.put("step", currentStep);
                addIfPresent(data, "sqlExecutionResult", state.value(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, ""));
                addIfPresent(data, "errorMsg", state.value(SqlAgentSpec.StateKey.SQL_ERROR, ""));
                data.put("fixAttemptCount", readInt(state, SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT));
                break;
            case SqlAgentSpec.Node.SQL_FIXER:
                data.put("step", currentStep);
                data.put("sql", state.value(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, ""));
                data.put("fixAttemptCount", readInt(state, SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT));
                break;
            case SqlAgentSpec.Node.PYTHON_GENERATION:
                data.put("pythonCode", state.value(SqlAgentSpec.StateKey.PYTHON_CODE, ""));
                data.put("step", currentStep);
                break;
            case SqlAgentSpec.Node.PYTHON_EXECUTION:
                data.put("pythonResult", state.value(SqlAgentSpec.StateKey.PYTHON_RESULT, ""));
                data.put("step", currentStep);
                break;
            case SqlAgentSpec.Node.PYTHON_ANALYSIS:
                data.put("analysis", state.value(SqlAgentSpec.StateKey.PYTHON_ANALYSIS_RESULT, ""));
                data.put("step", currentStep);
                data.put("executionOutput", state.value(SqlAgentSpec.StateKey.EXECUTION_OUTPUT, Map.of()));
                break;
            case SqlAgentSpec.Node.REPORT:
                data.put("report", state.value(SqlAgentSpec.StateKey.REPORT_RESULT, ""));
                break;
            default:
                for (String key : state.data().keySet()) {
                    if (!SENSITIVE_KEYS.contains(key)) {
                        Object val = state.value(key, null);
                        if (val != null) {
                            data.put(key, val);
                        }
                    }
                }
                break;
        }
        return data;
    }

    private void addIfPresent(Map<String, Object> data, String key, String value) {
        if (value != null && !value.isBlank()) {
            data.put(key, value);
        }
    }

    private Integer readInt(OverAllState state, String key) {
        Object v = state.value(key, (Integer) null);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<String> extractFilteredTableNames(OverAllState state) {
        Object tableNamesObj = state.value(SqlAgentSpec.StateKey.TABLE_NAMES, null);
        if (tableNamesObj instanceof List<?>) {
            List<String> names = (List<String>) tableNamesObj;
            if (!names.isEmpty()) {
                return names;
            }
        }
        String tableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        if (tableRelation != null && !tableRelation.isBlank()) {
            return Pattern.compile("# Table:\\s*(\\w+)")
                    .matcher(tableRelation)
                    .results()
                    .map(m -> m.group(1))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    /** Escape a message fragment for safe inline JSON strings (best-effort). */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}