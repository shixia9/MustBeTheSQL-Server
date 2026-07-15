package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.tool.ToolDefinition;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import com.sql.logic.engine.domain.agent.tool.mcp.McpServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes an MCP tool call as part of the agent execution graph.
 * <p>
 * Supports chained MCP calls via {@code $step_N.field} and {@code $prev.field}
 * reference syntax in parameter values. Before calling the tool, unresolved
 * references are substituted with actual values from {@code MCP_STEP_RESULTS}.
 * After a successful call, the parsed result is stored back into
 * {@code MCP_STEP_RESULTS} for downstream steps.
 * <p>
 * On failure, sets {@code MCP_CALL_FAILED = true} so the conditional
 * edge routes to {@code MCP_TOOL_FIXER} for repair and retry.
 */
@Component
public class McpToolExecutorNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutorNode.class);

    /** Matches $step_N.field — captures step number and dot-separated field path. */
    private static final Pattern REF_STEP = Pattern.compile("\\$step_(\\d+)\\.(.+)");
    /** Matches $prev.field — captures the dot-separated field path. */
    private static final Pattern REF_PREV = Pattern.compile("\\$prev\\.(.+)");

    private final McpServerManager mcpServerManager;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    public McpToolExecutorNode(McpServerManager mcpServerManager, ObjectMapper objectMapper,
                               ToolRegistry toolRegistry) {
        this.mcpServerManager = mcpServerManager;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String toolName = state.value(SqlAgentSpec.StateKey.MCP_TOOL_NAME, "");
        String paramsJson = state.value(SqlAgentSpec.StateKey.MCP_TOOL_PARAMS, "{}");
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);

        Map<String, Object> out = new LinkedHashMap<>();

        if (toolName == null || toolName.isBlank()) {
            log.warn("[McpToolExecutorNode] No MCP tool name in state — skipping");
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, "{}");
            out.put(SqlAgentSpec.StateKey.MCP_CALL_FAILED, false);
            out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep + 1);
            return out;
        }

        // Parse parameters
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(paramsJson, Map.class);
        } catch (Exception e) {
            log.warn("[McpToolExecutorNode] Failed to parse MCP params JSON: {}", e.getMessage());
            String errJson = "{\"error\":\"Invalid JSON parameters: " + escapeJson(e.getMessage()) + "\",\"rawParams\":\"" + escapeJson(paramsJson) + "\"}";
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, errJson);
            out.put(SqlAgentSpec.StateKey.MCP_CALL_FAILED, true);
            out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep); // keep step for retry
            return out;
        }

        // Resolve $step_N.field and $prev.field references from previous MCP results
        Map<Integer, Map<String, Object>> stepResults = readStepResults(state);
        String unresolved = objectMapper.writeValueAsString(params);
        resolveReferences(params, stepResults, currentStep);
        String resolvedJson = objectMapper.writeValueAsString(params);
        if (!resolvedJson.equals(unresolved)) {
            log.info("[McpToolExecutorNode] Resolved $ref placeholders: {} → {}", unresolved, resolvedJson);
        }

        // Validate required fields against inputSchema (after resolution so $refs are resolved)
        String validationError = validateParams(toolName, params);
        if (validationError != null) {
            log.warn("[McpToolExecutorNode] Parameter validation failed for {}: {}", toolName, validationError);
            String errJson = "{\"error\":\"Parameter validation failed: " + escapeJson(validationError) + "\",\"rawParams\":" + paramsJson + "}";
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, errJson);
            out.put(SqlAgentSpec.StateKey.MCP_CALL_FAILED, true);
            out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep); // keep step for retry
            return out;
        }

        String summary;
        log.info("[McpToolExecutorNode] Calling MCP tool: {} with params: {}", toolName, resolvedJson);
        try {
            String result = mcpServerManager.callTool(toolName, params);
            String resultText = result != null ? result : "{}";
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, resultText);
            out.put(SqlAgentSpec.StateKey.MCP_CALL_FAILED, false);
            summary = "[MCP Tool: " + toolName + "] 调用成功，返回 " + resultText.length() + " 字符。\n" +
                    (resultText.length() > 500 ? resultText.substring(0, 500) + "..." : resultText);
            log.info("[McpToolExecutorNode] MCP tool {} returned {} chars", toolName, resultText.length());

            // Store parsed result in step results for downstream $ref resolution
            try {
                Map<String, Object> parsed = objectMapper.readValue(resultText, Map.class);
                stepResults.put(currentStep, parsed);
                out.put(SqlAgentSpec.StateKey.MCP_STEP_RESULTS, stepResults);
            } catch (Exception e) {
                // Result is not JSON — store as text keyed by "result"
                log.debug("[McpToolExecutorNode] MCP result is not JSON, storing as raw text");
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("result", resultText);
                stepResults.put(currentStep, wrapper);
                out.put(SqlAgentSpec.StateKey.MCP_STEP_RESULTS, stepResults);
            }
        } catch (Exception e) {
            log.warn("[McpToolExecutorNode] MCP tool {} failed: {}", toolName, e.getMessage());
            String errJson = "{\"error\":\"" + escapeJson(e.getMessage()) + "\",\"toolName\":\"" + escapeJson(toolName) + "\",\"params\":" + resolvedJson + "}";
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, errJson);
            out.put(SqlAgentSpec.StateKey.MCP_CALL_FAILED, true);
            out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep); // keep step for retry
            summary = "[MCP Tool: " + toolName + "] 调用失败 — " + e.getMessage();
        }

        // Accumulate into EXECUTION_OUTPUT (same pattern as PythonAnalyzeNode)
        Map<String, String> executionOutput = new LinkedHashMap<>();
        Object existing = state.value(SqlAgentSpec.StateKey.EXECUTION_OUTPUT, (Object) null);
        if (existing instanceof Map) {
            ((Map<String, String>) existing).forEach((k, v) -> executionOutput.put(k, v));
        }
        executionOutput.put("step_" + currentStep, summary);
        out.put(SqlAgentSpec.StateKey.EXECUTION_OUTPUT, executionOutput);
        // Only advance step on success; failure keeps the same step for retry
        if (!Boolean.TRUE.equals(out.get(SqlAgentSpec.StateKey.MCP_CALL_FAILED))) {
            out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep + 1);
        }

        return out;
    }

    /**
     * Walk all string values in params recursively and replace $step_N.field and
     * $prev.field references with actual values from stepResults.
     */
    @SuppressWarnings("unchecked")
    private void resolveReferences(Map<String, Object> params, Map<Integer, Map<String, Object>> stepResults, int currentStep) {
        if (params == null || stepResults.isEmpty()) return;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String s) {
                String resolved = resolveRef(s, stepResults, currentStep);
                if (!resolved.equals(s)) {
                    // Try to coerce to appropriate type: if the resolved value looks like a number, parse it
                    entry.setValue(coerceValue(resolved));
                }
            } else if (value instanceof Map) {
                resolveReferences((Map<String, Object>) value, stepResults, currentStep);
            } else if (value instanceof List) {
                for (int i = 0; i < ((List<Object>) value).size(); i++) {
                    Object item = ((List<Object>) value).get(i);
                    if (item instanceof String s) {
                        String resolved = resolveRef(s, stepResults, currentStep);
                        if (!resolved.equals(s)) {
                            ((List<Object>) value).set(i, coerceValue(resolved));
                        }
                    } else if (item instanceof Map) {
                        resolveReferences((Map<String, Object>) item, stepResults, currentStep);
                    }
                }
            }
        }
    }

    /** Resolve a single string value that may be a $ref pattern. */
    private String resolveRef(String value, Map<Integer, Map<String, Object>> stepResults, int currentStep) {
        // Only attempt resolution if the value looks like a reference
        if (value == null || !value.startsWith("$")) return value;

        // Exact match: $step_N.field (whole value is a single reference)
        Matcher mStep = REF_STEP.matcher(value);
        if (mStep.matches()) {
            int refStep = Integer.parseInt(mStep.group(1));
            String fieldPath = mStep.group(2);
            Object resolved = resolveFieldPath(stepResults.get(refStep), fieldPath);
            return resolved != null ? String.valueOf(resolved) : value;
        }

        Matcher mPrev = REF_PREV.matcher(value);
        if (mPrev.matches()) {
            String fieldPath = mPrev.group(1);
            // $prev refers to the most recent step before currentStep
            int prevStep = 0;
            for (int s : stepResults.keySet()) {
                if (s < currentStep && s > prevStep) prevStep = s;
            }
            if (prevStep > 0) {
                Object resolved = resolveFieldPath(stepResults.get(prevStep), fieldPath);
                return resolved != null ? String.valueOf(resolved) : value;
            }
        }

        return value;
    }

    /** Follow a dot-separated path into a nested Map (e.g. "data.date" → map.get("data").get("date")). */
    @SuppressWarnings("unchecked")
    private Object resolveFieldPath(Map<String, Object> source, String path) {
        if (source == null || path == null || path.isBlank()) return null;
        String[] segments = path.split("\\.");
        Object current = source;
        for (String seg : segments) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(seg);
            } else {
                return null;
            }
        }
        return current;
    }

    /**
     * Coerce a string value to an appropriate Java type.
     * If it looks like an integer/long, parse it as such so JSON serialization
     * doesn't quote it when passed to the MCP tool.
     */
    private Object coerceValue(String s) {
        if (s == null) return null;
        // Try integer
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        // Try long
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        // Try double
        try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        // Try boolean
        if ("true".equalsIgnoreCase(s)) return true;
        if ("false".equalsIgnoreCase(s)) return false;
        return s;
    }

    /** Validate params against the tool's inputSchema required fields. Returns error message or null. */
    private String validateParams(String toolName, Map<String, Object> params) {
        ToolDefinition tool = toolRegistry.get(toolName);
        if (tool == null || tool.parametersSchema() == null || tool.parametersSchema().isBlank()) {
            return null;
        }
        try {
            JsonNode schema = objectMapper.readTree(tool.parametersSchema());
            if (!schema.has("required") || !schema.get("required").isArray()) {
                return null;
            }
            JsonNode props = schema.has("properties") ? schema.get("properties") : null;
            StringBuilder missing = new StringBuilder();
            for (JsonNode f : schema.get("required")) {
                String fieldName = f.asText();
                Object value = params.get(fieldName);
                if (value == null || (value instanceof String && ((String) value).isBlank())) {
                    if (!missing.isEmpty()) missing.append(", ");
                    missing.append(fieldName);
                    if (props != null && props.has(fieldName) && props.get(fieldName).has("type")) {
                        missing.append("(type: ").append(props.get(fieldName).get("type").asText()).append(")");
                    }
                }
            }
            return missing.isEmpty() ? null : "Missing required fields: " + missing;
        } catch (Exception e) {
            log.warn("[McpToolExecutorNode] Schema validation parse error: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, Map<String, Object>> readStepResults(OverAllState state) {
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        Object existing = state.value(SqlAgentSpec.StateKey.MCP_STEP_RESULTS, (Object) null);
        if (existing instanceof Map) {
            Map<?, ?> raw = (Map<?, ?>) existing;
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                try {
                    int step = Integer.parseInt(String.valueOf(e.getKey()));
                    if (e.getValue() instanceof Map) {
                        result.put(step, (Map<String, Object>) e.getValue());
                    }
                } catch (NumberFormatException ignored) { /* skip malformed entries */ }
            }
        }
        return result;
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private int readInt(OverAllState state, String key, int dflt) {
        Object v = state.value(key, (Integer) null);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) {
            Long l = AgentStateUtil.toLong(v);
            return l == null ? dflt : l.intValue();
        }
    }
}
