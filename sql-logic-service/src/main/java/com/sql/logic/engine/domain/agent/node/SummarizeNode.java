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
 * Summarize Node (Phase B) — aggregates subtask results into a final report.
 * <p>
 * Reads {@code SUBTASKS}, {@code SUBTASK_RESULTS}, and {@code EXECUTION_OUTPUT}
 * to build a structured summary of all completed subtask executions, then asks
 * the LLM to produce a cohesive final report. The result is written to
 * {@code REPORT_RESULT}, reusing the same state key that {@code ReportNode}
 * uses, so the frontend receives a consistent output shape.
 */
@Component
public class SummarizeNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(SummarizeNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final LlmCallReporter llmCallReporter;

    public SummarizeNode(LlmClientManager llmClientManager,
                         PromptManager promptManager,
                         LlmCallReporter llmCallReporter) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.llmCallReporter = llmCallReporter;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }

        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));

        StringBuilder subtaskResults = new StringBuilder();

        String subtaskResultsStr = state.value(SqlAgentSpec.StateKey.SUBTASK_RESULTS, "");
        if (subtaskResultsStr != null && !subtaskResultsStr.isBlank()) {
            subtaskResults.append(subtaskResultsStr).append("\n");
        }

        String executionOutput = renderExecutionOutput(state);
        if (executionOutput != null && !executionOutput.isBlank()) {
            subtaskResults.append(executionOutput);
        }

        if (subtaskResults.isEmpty()) {
            subtaskResults.append("（无子任务执行结果）");
        }

        String prompt = promptManager.render(SqlAgentSpec.PromptName.SUMMARIZE, Map.of(
                "rewrite_query", rewriteQuery,
                "subtask_results", subtaskResults.toString()
        ));

        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.SUMMARIZE, llmCallReporter);
        String report = strategy.generateSql(prompt, null);

        log.info("[SummarizeNode] Report generated, length={}", report == null ? 0 : report.length());

        return Map.of(SqlAgentSpec.StateKey.REPORT_RESULT, report == null ? "" : report);
    }

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
