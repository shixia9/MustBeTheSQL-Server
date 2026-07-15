package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Report Generation Node — produces a final summary report from the SQL execution results.
 * <p>
 * Phase 1 version: simple report based on rewrite query + generated SQL + execution result.
 * Phase 3+ will include full plan execution results and Python analysis outputs.
 */
@Component
public class ReportNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(ReportNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final LlmCallReporter llmCallReporter;

    public ReportNode(LlmClientManager llmClientManager, PromptManager promptManager,
                      LlmCallReporter llmCallReporter) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.llmCallReporter = llmCallReporter;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        String sql = state.value(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, "");
        String sqlResult = state.value(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, "");

        Object llmConfigIdObj = state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null);
        Long llmConfigId = AgentStateUtil.toLong(llmConfigIdObj);
        Object userIdObj = state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null);
        Long userId = AgentStateUtil.toLong(userIdObj);

        // If no rewritten query, fall back to original input
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }

        // Short-circuit: the FeasibilityAssessmentNode (clarify/chat branch) may have
        // already pre-filled REPORT_RESULT with the verdict text. Emit it as-is.
        String prefilled = state.value(SqlAgentSpec.StateKey.REPORT_RESULT, "");
        if (prefilled != null && !prefilled.isBlank()) {
            log.info("[ReportNode] Emitting pre-filled report (feasibility verdict path), length={}", prefilled.length());
            return Map.of(SqlAgentSpec.StateKey.REPORT_RESULT, prefilled);
        }

        // Build analysis steps description. Phase 4: fold in any Python analysis
        // conclusions accumulated in EXECUTION_OUTPUT (a per-step map) alongside SQL.
        StringBuilder analysisSteps = new StringBuilder();
        analysisSteps.append("### 步骤 : SQL 查询\n");
        analysisSteps.append("**使用工具**: SQL_GENERATION\n");
        analysisSteps.append("**执行SQL**: \n```sql\n").append(sql).append("\n```\n\n");

        if (sqlResult != null && !sqlResult.isBlank()) {
            analysisSteps.append("**执行结果**: \n```\n").append(sqlResult).append("\n```\n");
        } else {
            analysisSteps.append("**执行结果**: 暂无（SQL未执行）\n");
        }

        String pythonAnalysisText = renderExecutionOutput(state);
        if (pythonAnalysisText != null && !pythonAnalysisText.isBlank()) {
            analysisSteps.append("\n### 步骤 : Python 深度分析\n");
            analysisSteps.append("**使用工具**: PYTHON_GENERATION\n");
            analysisSteps.append("**分析结论**:\n").append(pythonAnalysisText).append("\n");
        }

        // Render the report prompt
        String userMemory = state.value(SqlAgentSpec.StateKey.USER_MEMORY, "");
        String userMemorySection = (userMemory == null || userMemory.isBlank())
                ? "无"
                : userMemory;
        String conversationHistory = state.value(SqlAgentSpec.StateKey.CONVERSATION_HISTORY, "");
        String conversationHistorySection = (conversationHistory == null || conversationHistory.isBlank())
                ? "无"
                : conversationHistory;
        String systemPrompt = state.value(SqlAgentSpec.StateKey.AGENT_SYSTEM_PROMPT, "");
        String systemPromptSection = (systemPrompt == null || systemPrompt.isBlank())
                ? ""
                : "# 附加角色要求 (来自 Agent 配置)\n" + systemPrompt;
        String prompt = promptManager.render(SqlAgentSpec.PromptName.REPORT_GENERATOR, Map.of(
                "user_requirements_and_plan", "用户需求: " + rewriteQuery,
                "analysis_steps_and_data", analysisSteps.toString(),
                "summary_and_recommendations", "请给出总结与建议",
                "json_example", "{}",
                "user_memory_section", userMemorySection,
                "conversation_history_section", conversationHistorySection,
                "system_prompt_section", systemPromptSection,
                "optimization_section", ""
        ));

        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.REPORT, llmCallReporter);
        String report = strategy.generateSql(prompt, null);

        log.info("[ReportNode] Report generated, length={}", report.length());

        return Map.of(SqlAgentSpec.StateKey.REPORT_RESULT, report);
    }

    /**
     * Render the {@code EXECUTION_OUTPUT} per-step analysis map into a single readable
     * block for the report prompt. Returns an empty string when nothing was accumulated.
     */
    @SuppressWarnings("unchecked")
    private String renderExecutionOutput(OverAllState state) {
        Object existing = state.value(SqlAgentSpec.StateKey.EXECUTION_OUTPUT, (Object) null);
        if (!(existing instanceof Map)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        ((Map<String, String>) existing).forEach((key, value) -> {
            sb.append("- ").append(key).append(": ").append(value == null ? "" : value).append("\n");
        });
        return sb.toString().trim();
    }
}