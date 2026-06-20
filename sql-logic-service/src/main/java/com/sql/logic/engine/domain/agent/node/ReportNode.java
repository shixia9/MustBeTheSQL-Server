package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
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

    public ReportNode(LlmClientManager llmClientManager, PromptManager promptManager) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        String sql = state.value(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, "");
        String sqlResult = state.value(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, "");

        Object llmConfigIdObj = state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, null);
        Long llmConfigId = llmConfigIdObj instanceof Long ? (Long) llmConfigIdObj : null;
        Object userIdObj = state.value(SqlAgentSpec.StateKey.USER_ID, null);
        Long userId = userIdObj instanceof Long ? (Long) userIdObj : null;

        // If no rewritten query, fall back to original input
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }

        // Build analysis steps description
        StringBuilder analysisSteps = new StringBuilder();
        analysisSteps.append("### 步骤 1: SQL查询\n");
        analysisSteps.append("**使用工具**: SQL_GENERATION\n");
        analysisSteps.append("**执行SQL**: \n```sql\n").append(sql).append("\n```\n\n");

        if (sqlResult != null && !sqlResult.isBlank()) {
            analysisSteps.append("**执行结果**: \n```\n").append(sqlResult).append("\n```\n");
        } else {
            analysisSteps.append("**执行结果**: 暂无（SQL未执行）\n");
        }

        // Render the report prompt
        String prompt = promptManager.render(SqlAgentSpec.PromptName.REPORT_GENERATOR, Map.of(
                "user_requirements_and_plan", "用户需求: " + rewriteQuery,
                "analysis_steps_and_data", analysisSteps.toString(),
                "summary_and_recommendations", "请给出总结与建议",
                "json_example", "{}",
                "optimization_section", ""
        ));

        LLMStrategy strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
        String report = strategy.generateSql(prompt, null);

        log.info("[ReportNode] Report generated, length={}", report.length());

        return Map.of(SqlAgentSpec.StateKey.REPORT_RESULT, report);
    }
}