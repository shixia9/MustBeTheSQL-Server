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
        if (mcpTools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("## 4. 外部 MCP 工具 (Available MCP Tools)\n\n");
        sb.append("你可以使用以下外部工具来获取外部数据。在步骤中使用工具名称（原始名称，例如 `search_tickets`）作为 `tool_to_use`，instruction 填写 JSON 格式的调用参数。\n\n");
        for (ToolDefinition tool : mcpTools) {
            sb.append("### ").append(tool.displayName()).append(" (`").append(tool.name()).append("`)\n");
            sb.append("- **描述**: ").append(tool.description()).append("\n");
            if (tool.parametersSchema() != null) {
                sb.append("- **参数格式**: ").append(tool.parametersSchema()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("**使用 MCP 工具的步骤格式示例**:\n");
        sb.append("```json\n{\n  \"step\": 1,\n  \"tool_to_use\": \"search_tickets\",\n");
        sb.append("  \"tool_parameters\": {\"instruction\": \"{\\\"from\\\":\\\"北京\\\",\\\"to\\\":\\\"上海\\\",\\\"date\\\":\\\"2026-07-20\\\"}\"}\n}\n```\n");
        return sb.toString();
    }
}