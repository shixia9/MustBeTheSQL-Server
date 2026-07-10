package com.sql.logic.engine.domain.agent.core;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Encodes graph {@link NodeOutput}s (FINISHED) and synthetic STARTED events into
 * the SSE JSON strings streamed by {@code SqlAgentController}. Extracted from the
 * controller so that both the controller and the graph lifecycle listener
 * ({@code AgentSseStartedListener}) share one encoding path.
 *
 * <p>Also owns the per-node step buffering + {@link TraceContext} recording that
 * fires on FINISHED: appends a step to {@link AgentRunContext} and records the
 * step's timing/type (token fields are owned by {@code TraceContext.addTokensToCurrentNode}
 * via {@code TracingLlmClientWrapper}, so they are deliberately NOT touched here).
 */
@Component
public class AgentSseCodec {

    private static final Logger log = LoggerFactory.getLogger(AgentSseCodec.class);

    private final ObjectMapper objectMapper;

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "connectionId", "llmConfigId", "userId"
    );

    private static final Map<String, String> NODE_MESSAGE_TYPES = java.util.Map.ofEntries(
            java.util.Map.entry("MEMORY_RECALL", "THINKING"),
            java.util.Map.entry("EVIDENCE_RECALL", "THINKING"),
            java.util.Map.entry("SCHEMA_LINKING", "THINKING"),
            java.util.Map.entry("FEASIBILITY_ASSESSMENT", "THINKING"),
            java.util.Map.entry("PLANNER", "THINKING"),
            java.util.Map.entry("HITL_GATE", "STATUS"),
            java.util.Map.entry("HITL", "STATUS"),
            java.util.Map.entry("PLAN_DISPATCH", "STATUS"),
            java.util.Map.entry("SQL_GENERATION", "TOOL_CALL"),
            java.util.Map.entry("SQL_EXECUTION", "TOOL_RESULT"),
            java.util.Map.entry("SQL_FIXER", "THINKING"),
            java.util.Map.entry("PYTHON_GENERATION", "TOOL_CALL"),
            java.util.Map.entry("PYTHON_EXECUTION", "TOOL_RESULT"),
            java.util.Map.entry("PYTHON_ANALYSIS", "THINKING"),
            java.util.Map.entry("REPORT", "REPORT")
    );

    public AgentSseCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String messageTypeForNode(String nodeName) {
        return NODE_MESSAGE_TYPES.getOrDefault(nodeName, "STATUS");
    }

    /** Build the STARTED SSE event for a node (emitted by the lifecycle listener). */
    public String startedJson(String nodeName) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("nodeName", nodeName);
            event.put("outputType", "STARTED");
            event.put("messageType", messageTypeForNode(nodeName));
            event.put("sequenceNo", 0);
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("[AgentSseCodec] Failed to encode STARTED for {}: {}", nodeName, e.getMessage());
            return "";
        }
    }

    /** Encode a completed {@link NodeOutput} as the FINISHED SSE event, and buffer the
     *  step + record its trace timing (tokens are owned elsewhere). Returns "" for
     *  START/END pseudo-nodes (filtered by the caller). */
    public String nodeOutputToJson(NodeOutput output, AgentRunContext runContext) {
        try {
            String nodeName = output.node();

            if ("__start__".equalsIgnoreCase(nodeName) || "__end__".equalsIgnoreCase(nodeName)) {
                return "";
            }

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("nodeName", nodeName);
            event.put("outputType", "FINISHED");
            event.put("messageType", messageTypeForNode(nodeName));
            event.put("sequenceNo", 0); // set below after appendStep

            String outputDataJson = null;
            OverAllState state = output.state();
            if (state != null) {
                Map<String, Object> data = extractNodeData(nodeName, state);
                event.put("data", data);
                try {
                    outputDataJson = objectMapper.writeValueAsString(data);
                } catch (Exception ignore) { /* best-effort */ }
            }

            // Buffer the per-node step + record trace timing/type. Tokens are NOT
            // touched here — they were accumulated during the LLM call via
            // TraceContext.addTokensToCurrentNode (called from TracingLlmClientWrapper).
            if (runContext != null) {
                try {
                    int seq = runContext.appendStep(nodeName, "SUCCESS", null, outputDataJson);
                    event.put("sequenceNo", seq);
                    TraceContext tc = runContext.getTraceContext();
                    if (tc != null) {
                        // beginNode was called by the STARTED listener; recordStep
                        // finalizes latency/type and endNode computes durationMs.
                        tc.recordStep(seq, nodeName, "SUCCESS", 0, 0, messageTypeForNode(nodeName));
                    }
                } catch (Exception ignore) { /* sequencing only */ }
            }

            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("[AgentSseCodec] Failed to serialize NodeOutput", e);
            return "{\"nodeName\":\"ERROR\",\"outputType\":\"ERROR\",\"message\":\"" + escape(e.getMessage()) + "\"}";
        }
    }

    private Map<String, Object> extractNodeData(String nodeName, OverAllState state) {
        Map<String, Object> data = new LinkedHashMap<>();
        Integer currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP);

        switch (nodeName) {
            case SqlAgentSpec.Node.MEMORY_RECALL:
                data.put("userMemory", state.value(SqlAgentSpec.StateKey.USER_MEMORY, ""));
                break;
            case SqlAgentSpec.Node.EVIDENCE_RECALL:
                data.put("rewriteQuery", state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, ""));
                data.put("evidence", state.value(SqlAgentSpec.StateKey.EVIDENCE, ""));
                Object egl = state.value(SqlAgentSpec.StateKey.EVIDENCE_GLOSSARY, List.of());
                data.put("evidenceGlossary", egl != null ? egl : List.of());
                Object efaq = state.value(SqlAgentSpec.StateKey.EVIDENCE_FAQ, List.of());
                data.put("evidenceFaq", efaq != null ? efaq : List.of());
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
            @SuppressWarnings("unchecked")
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
    public String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}