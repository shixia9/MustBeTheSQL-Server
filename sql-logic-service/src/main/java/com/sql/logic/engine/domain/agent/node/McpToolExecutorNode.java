package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        Map<String, Object> out = new LinkedHashMap<>();
        if (toolName == null || toolName.isBlank()) {
            log.warn("[McpToolExecutorNode] No MCP tool name in state — skipping");
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, "{}");
            return out;
        }

        log.info("[McpToolExecutorNode] Calling MCP tool: {} with params: {}", toolName, paramsJson);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = objectMapper.readValue(paramsJson, Map.class);
            String result = mcpServerManager.callTool(toolName, params);
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, result != null ? result : "{}");
            log.info("[McpToolExecutorNode] MCP tool {} returned {} chars", toolName,
                    result != null ? result.length() : 0);
        } catch (Exception e) {
            log.warn("[McpToolExecutorNode] MCP tool {} failed: {}", toolName, e.getMessage());
            out.put(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
        return out;
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
