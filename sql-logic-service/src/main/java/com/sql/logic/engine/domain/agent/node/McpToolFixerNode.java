package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.tool.ToolDefinition;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import com.sql.logic.engine.domain.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP Tool Fixer Node — repairs failed MCP tool call parameters using the error.
 * <p>
 * Reads {@code MCP_TOOL_NAME}, {@code MCP_TOOL_PARAMS} (the failed params),
 * {@code MCP_TOOL_RESULT} (the error), and the tool's inputSchema from
 * {@link ToolRegistry}. Calls the LLM to produce corrected JSON parameters,
 * writes them back to {@code MCP_TOOL_PARAMS}, and increments
 * {@code MCP_FIX_ATTEMPT_COUNT}.
 * <p>
 * The conditional edge loops back to {@code MCP_TOOL_EXECUTOR} for a retry.
 * Capped at 2 attempts by {@code McpToolFixerEdge}.
 */
@Component
public class McpToolFixerNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(McpToolFixerNode.class);

    private final LlmClientManager llmClientManager;
    private final ObjectMapper objectMapper;
    private final LlmCallReporter llmCallReporter;
    private final ToolRegistry toolRegistry;

    public McpToolFixerNode(LlmClientManager llmClientManager,
                            ObjectMapper objectMapper,
                            LlmCallReporter llmCallReporter,
                            ToolRegistry toolRegistry) {
        this.llmClientManager = llmClientManager;
        this.objectMapper = objectMapper;
        this.llmCallReporter = llmCallReporter;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String toolName = state.value(SqlAgentSpec.StateKey.MCP_TOOL_NAME, "");
        String failedParams = state.value(SqlAgentSpec.StateKey.MCP_TOOL_PARAMS, "{}");
        String errorResult = state.value(SqlAgentSpec.StateKey.MCP_TOOL_RESULT, "{}");
        int fixAttempt = readInt(state, SqlAgentSpec.StateKey.MCP_FIX_ATTEMPT_COUNT, 0);
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);

        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));

        // Get the tool's inputSchema for context
        ToolDefinition tool = toolRegistry.get(toolName);
        String schemaInfo = "";
        if (tool != null && tool.parametersSchema() != null) {
            schemaInfo = tool.parametersSchema();
        }

        String prompt = buildFixPrompt(toolName, schemaInfo, failedParams, errorResult, fixAttempt);

        Map<String, Object> out = new LinkedHashMap<>();

        try {
            LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                    (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                    SqlAgentSpec.Node.MCP_TOOL_FIXER, llmCallReporter);
            String raw = strategy.generateSql(prompt, null);
            String fixed = raw == null ? "" : raw.trim();

            // Extract JSON from the LLM response (strip markdown fences, find {…})
            String fixedJson = extractJson(fixed);
            if (fixedJson == null || fixedJson.isBlank()) {
                log.warn("[McpToolFixerNode] LLM did not produce valid JSON for {} — keeping original params", toolName);
                fixedJson = failedParams;
            }

            // Validate the fixed JSON is parseable
            try {
                objectMapper.readTree(fixedJson);
            } catch (Exception e) {
                log.warn("[McpToolFixerNode] Fixed params not valid JSON: {} — keeping original", e.getMessage());
                fixedJson = failedParams;
            }

            log.info("[McpToolFixerNode] Fixed params for {} (attempt {}): {} → {}",
                    toolName, fixAttempt + 1, failedParams, fixedJson);

            out.put(SqlAgentSpec.StateKey.MCP_TOOL_PARAMS, fixedJson);
            out.put(SqlAgentSpec.StateKey.MCP_FIX_ATTEMPT_COUNT, fixAttempt + 1);
            out.put(SqlAgentSpec.StateKey.MCP_CALL_FAILED, false); // clear so executor retries
        } catch (Exception e) {
            log.warn("[McpToolFixerNode] LLM call failed for {}: {}", toolName, e.getMessage());
            // Give up — clear failed flag so we advance past this step
            out.put(SqlAgentSpec.StateKey.MCP_CALL_FAILED, false);
            out.put(SqlAgentSpec.StateKey.MCP_FIX_ATTEMPT_COUNT, fixAttempt + 1);
            out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep + 1); // skip this step
        }

        return out;
    }

    private String buildFixPrompt(String toolName, String schemaInfo, String failedParams,
                                   String errorResult, int fixAttempt) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个 MCP 工具调用修复专家。以下 MCP 工具调用失败了，请根据错误信息和工具 Schema 修正参数。\n\n");
        sb.append("**工具名称**: ").append(toolName).append("\n\n");

        if (schemaInfo != null && !schemaInfo.isBlank()) {
            sb.append("**工具参数 Schema (inputSchema)**:\n```json\n").append(schemaInfo).append("\n```\n\n");
        }

        sb.append("**上次调用时传入的参数**:\n```json\n").append(failedParams).append("\n```\n\n");
        sb.append("**错误信息**:\n```\n").append(errorResult).append("\n```\n\n");

        sb.append("**任务**: 分析错误原因，根据 Schema 修正参数。特别检查:\n");
        sb.append("1. 所有 required 字段是否已提供\n");
        sb.append("2. 字段类型是否正确 (string/number/boolean)\n");
        sb.append("3. 日期格式是否为 YYYY-MM-DD (10个字符)\n");
        sb.append("4. 字符串值是否有意义 (不是空字符串或占位符)\n\n");

        sb.append("**重要**: 只输出修正后的 JSON 参数对象，不要包含任何解释。输出必须是合法的单行 JSON 对象。\n");
        sb.append("输出格式示例: {\"from\":\"广州\",\"to\":\"南昌\",\"date\":\"2026-07-15\"}\n");

        return sb.toString();
    }

    /** Extract a JSON object from LLM output that may contain markdown fences. */
    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        // Strip markdown code fences
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            if (start < 0) start = 3;
            else start = start + 1;
            int end = s.lastIndexOf("```");
            if (end > start) s = s.substring(start, end).trim();
            else s = s.substring(start).trim();
        }
        // Find the outermost { … }
        int braceStart = s.indexOf('{');
        int braceEnd = s.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return s.substring(braceStart, braceEnd + 1);
        }
        return s;
    }

    private int readInt(OverAllState state, String key, int dflt) {
        Object v = state.value(key, (Integer) null);
        if (v == null) return dflt;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            Long asLong = AgentStateUtil.toLong(v);
            return asLong == null ? dflt : asLong.intValue();
        }
    }
}
