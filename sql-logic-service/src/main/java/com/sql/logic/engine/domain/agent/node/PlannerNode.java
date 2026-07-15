package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.AgentToolGate;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.Plan;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.tool.ToolDefinition;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import com.sql.logic.engine.domain.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner Node — produces a multi-step execution plan as JSON.
 * <p>
 * Renders {@code planner.st} with the recalled schema + evidence + a forced JSON
 * schema (via {@link BeanOutputConverter}), then parses the LLM output into a
 * {@link Plan}. The plan is stored in state as a JSON string under {@code PLAN}
 * (downstream nodes re-parse on demand — robust against OverAllState round-trips),
 * and {@code CURRENT_STEP} is initialised to 1.
 * <p>
 * No HITL in Phase 3: {@code plan_validation_error} is always empty.
 */
@Component
public class PlannerNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(PlannerNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper;
    private final LlmCallReporter llmCallReporter;
    private final ToolRegistry toolRegistry;

    public PlannerNode(LlmClientManager llmClientManager,
                        PromptManager promptManager,
                        ObjectMapper objectMapper,
                        LlmCallReporter llmCallReporter,
                        ToolRegistry toolRegistry) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.objectMapper = objectMapper;
        this.llmCallReporter = llmCallReporter;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }
        String tableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        String evidence = state.value(SqlAgentSpec.StateKey.EVIDENCE, "");

        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));

        String schema = (tableRelation == null || tableRelation.isBlank()) ? "（无召回的 Schema）" : tableRelation;
        String evidenceText = (evidence == null || evidence.isBlank()) ? "无" : evidence;

        // Force the LLM to emit a JSON object matching the Plan schema.
        BeanOutputConverter<Plan> converter = new BeanOutputConverter<>(Plan.class);

        // If the user rejected the previous plan, the HITL edge pushed back their
        // feedback via CONFIRMATION_FEEDBACK. Feed it into planner.st's "用户反馈处理" section
        // so the LLM must comply with the revision requirements. Empty on first run.
        String feedback = state.value(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, "");
        String planValidationError = (feedback == null || feedback.isBlank())
                ? "（暂无用户反馈）"
                : "用户反馈：" + feedback;

        // Inject conversation history so multi-turn follow-ups produce context-aware plans
        String conversationHistory = state.value(SqlAgentSpec.StateKey.CONVERSATION_HISTORY, "");
        String conversationHistorySection = (conversationHistory == null || conversationHistory.isBlank())
                ? "" : "## 历史对话上下文\n" + conversationHistory;

        // Tool gate: when python is disabled, tell the planner not to use PYTHON_GENERATE_NODE
        boolean pythonEnabled = AgentToolGate.isToolEnabled(state, AgentToolGate.TOOL_PYTHON);
        String pythonConstraint = pythonEnabled ? "" : "注意：当前不支持 Python 分析能力，请勿在计划中使用 PYTHON_GENERATE_NODE。所有分析步骤请使用 SQL_GENERATE_NODE。";

        // Build MCP tools section for the planner prompt
        String mcpToolsSection = buildMcpToolsSection();

        String prompt = promptManager.render(SqlAgentSpec.PromptName.PLANNER, Map.of(
                "user_question", rewriteQuery,
                "schema", schema,
                "evidence", evidenceText,
                "semantic_model", "（暂无语义模型，阶段5 接入）",
                "plan_validation_error", planValidationError,
                "conversation_history_section", conversationHistorySection,
                "python_constraint", pythonConstraint,
                "mcp_tools_section", mcpToolsSection,
                "format", converter.getFormat()
        ));

        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.PLANNER, llmCallReporter);
        String raw = strategy.generateSql(prompt, null);
        String rawPlan = raw == null ? "" : raw.trim();

        // Lenient parse: tolerate markdown fences / trailing prose.
        Plan plan = Plan.fromJson(objectMapper, rawPlan);

        Map<String, Object> out = new LinkedHashMap<>();
        if (plan == null || plan.executionPlan() == null || plan.executionPlan().isEmpty()) {
            log.warn("[PlannerNode] Failed to parse plan from LLM output (len={}):\n{}", rawPlan.length(), rawPlan);
            // Degraded path: a single REPORT step so the graph still terminates.
            Plan degraded = new Plan("无法解析执行计划，直接生成报告。", List.of());
            out.put(SqlAgentSpec.StateKey.PLAN, objectMapper.writeValueAsString(degraded));
        } else {
            log.info("[PlannerNode] Plan steps: {}", plan.executionPlan().size());
            out.put(SqlAgentSpec.StateKey.PLAN, objectMapper.writeValueAsString(plan));
        }

        // (Re)initialise the cursor at the first step. Reset repair counters too.
        out.put(SqlAgentSpec.StateKey.CURRENT_STEP, 1);
        out.put(SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT, 0);
        out.put(SqlAgentSpec.StateKey.SQL_ERROR, "");
        // Clear the consumed feedback so a subsequent reject doesn't re-inject stale text.
        out.put(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, "");

        return out;
    }

    /** Build a prompt section describing available MCP tools for the planner. */
    private String buildMcpToolsSection() {
        List<ToolDefinition> mcpTools = toolRegistry.listTools().stream()
                .filter(t -> t.type().name().startsWith("MCP_"))
                .toList();
        if (mcpTools.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## 4. 外部 MCP 工具 (Available MCP Tools)\n\n");
        sb.append("以下外部工具可直接调用以获取实时数据。在 `tool_to_use` 中使用工具的精确名称。\n");
        sb.append("**重要**: 使用 `mcp_params` 字段直接传递 JSON 对象参数（不需要转义，直接写 JSON 对象即可）。\n");
        sb.append("**日期格式**: 所有日期字段必须使用 `YYYY-MM-DD` 格式（如 `\"2026-07-15\"`，10个字符）。\n\n");
        for (ToolDefinition tool : mcpTools) {
            sb.append("- **`").append(tool.name()).append("`**: ").append(tool.description()).append("\n");
            if (tool.parametersSchema() != null) {
                sb.append("  参数Schema: ").append(tool.parametersSchema()).append("\n");
                // Parse required fields from inputSchema for explicit hint
                String requiredHint = parseRequiredFields(tool.parametersSchema());
                if (!requiredHint.isEmpty()) {
                    sb.append("  **必填字段**: ").append(requiredHint).append("\n");
                }
            }
            sb.append("\n");
        }
        sb.append("**步骤格式** (mcp_params 直接写 JSON 对象，无需转义):\n```json\n");
        sb.append("{\n  \"step\": 1,\n  \"tool_to_use\": \"search_tickets\",\n");
        sb.append("  \"tool_parameters\": {\n    \"mcp_params\": {\n      \"from\": \"广州\",\n      \"to\": \"南昌\",\n      \"date\": \"2026-07-15\"\n    }\n  }\n}\n```\n\n");
        sb.append("### MCP 链式调用 (Chained MCP Calls)\n\n");
        sb.append("当一个 MCP 工具的输出需要作为另一个 MCP 工具的输入时，使用 `$step_N.field` 或 `$prev.field` 引用语法。\n");
        sb.append("- `$step_N.field` — 引用第 N 步的返回结果中的 field 字段（支持嵌套路径如 `$step_1.data.date`）\n");
        sb.append("- `$prev.field` — 引用最近一个已完成步骤的返回结果中的 field 字段\n\n");
        sb.append("**示例：先获取当前日期，再用日期查询车票**\n");
        sb.append("```json\n{\n  \"execution_plan\": [\n");
        sb.append("    {\n      \"step\": 1,\n      \"tool_to_use\": \"get-current-date\",\n");
        sb.append("      \"tool_parameters\": { \"mcp_params\": {} }\n");
        sb.append("    },\n");
        sb.append("    {\n      \"step\": 2,\n      \"tool_to_use\": \"get-tickets\",\n");
        sb.append("      \"tool_parameters\": {\n");
        sb.append("        \"mcp_params\": {\n");
        sb.append("          \"from\": \"广州\",\n");
        sb.append("          \"to\": \"南昌\",\n");
        sb.append("          \"date\": \"$step_1.date\"\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  ]\n}\n```\n");
        sb.append("**注意**: 只有步骤 N 已执行成功后，`$step_N.field` 才能被正确解析。请根据各工具的返回 Schema 推断可引用的字段名。\n");
        return sb.toString();
    }

    /** Extract a short required-fields hint from a JSON schema string. */
    private String parseRequiredFields(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) return "";
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(schemaJson);
            if (root.has("required") && root.get("required").isArray()) {
                StringBuilder hint = new StringBuilder();
                for (com.fasterxml.jackson.databind.JsonNode f : root.get("required")) {
                    if (!hint.isEmpty()) hint.append(", ");
                    hint.append("`").append(f.asText()).append("`");
                }
                // Append type hints for required fields
                if (root.has("properties")) {
                    com.fasterxml.jackson.databind.JsonNode props = root.get("properties");
                    hint.append(" (");
                    boolean first = true;
                    for (com.fasterxml.jackson.databind.JsonNode f : root.get("required")) {
                        String name = f.asText();
                        if (props.has(name) && props.get(name).has("type")) {
                            if (!first) hint.append(", ");
                            hint.append(name).append(": ").append(props.get(name).get("type").asText());
                            first = false;
                        }
                    }
                    hint.append(")");
                }
                return hint.toString();
            }
        } catch (Exception ignore) { /* best-effort */ }
        return "";
    }
}