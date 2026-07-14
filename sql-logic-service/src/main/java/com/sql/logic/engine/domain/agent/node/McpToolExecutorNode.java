package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.tool.mcp.McpServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes an MCP tool call as part of the agent execution graph.
 * <p>
 * Reads {@code MCP_TOOL_NAME} and {@code MCP_TOOL_PARAMS} from state,
 * delegates to {@link McpServerManager#callTool(String, Map)},
 * and writes the serialised result to {@code MCP_TOOL_RESULT}.
 */
@Component
public class McpToolExecutorNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(McpToolExecutorNode.class);

    private final McpServerManager mcpServerManager;
    private final ObjectMapper objectMapper;

    public McpToolExecutorNode(McpServerManager mcpServerManager, ObjectMapper objectMapper) {
        this.mcpServerManager = mcpServerManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String toolName = state.value(SqlAgentSpec.StateKey.MCP_TOOL_NAME, "");
        String paramsJson = state.value(SqlAgentSpec.StateKey.MCP_TOOL_PARAMS, "{}");
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);

        Map<String, Object> out = new LinkedHashMap<>();

        if (toolName == null || toolName.isBlank()) {
            log.warn("[McpToolExecutorNode] No MCP tool name in state — skipping");
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, "{}");
            out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep + 1);
            return out;
        }

        String summary;
        log.info("[McpToolExecutorNode] Calling MCP tool: {} with params: {}", toolName, paramsJson);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = objectMapper.readValue(paramsJson, Map.class);
            String result = mcpServerManager.callTool(toolName, params);
            String resultText = result != null ? result : "{}";
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, resultText);
            // Build a brief summary for the report node
            summary = "[MCP Tool: " + toolName + "] 调用成功，返回 " + resultText.length() + " 字符。\n" +
                    (resultText.length() > 500 ? resultText.substring(0, 500) + "..." : resultText);
            log.info("[McpToolExecutorNode] MCP tool {} returned {} chars", toolName, resultText.length());
        } catch (Exception e) {
            log.warn("[McpToolExecutorNode] MCP tool {} failed: {}", toolName, e.getMessage());
            String errJson = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, errJson);
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
        out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep + 1);

        return out;
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
