package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Analyzer Node — complexity classification of the user query.
 * <p>
 * After evidence recall and schema linking, this node asks the LLM to classify
 * the query as {@code SIMPLE}, {@code COMPLEX}, or {@code CLARIFY} and writes
 * the verdict into {@code COMPLEXITY} along with a human-readable
 * {@code complexityReason}. The downstream {@code AnalyzerEdge} routes on this value.
 * <p>
 * On any error the node degrades gracefully to {@code SIMPLE}, never blocking the graph.
 */
@Component
public class AnalyzerNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(AnalyzerNode.class);

    private static final int SUMMARY_MAX_LENGTH = 500;

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper;
    private final LlmCallReporter llmCallReporter;

    public AnalyzerNode(LlmClientManager llmClientManager,
                        PromptManager promptManager,
                        ObjectMapper objectMapper,
                        LlmCallReporter llmCallReporter) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.objectMapper = objectMapper;
        this.llmCallReporter = llmCallReporter;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }
        String tableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        String evidence = state.value(SqlAgentSpec.StateKey.EVIDENCE, "");

        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));

        String schemaSummary = truncate(tableRelation, SUMMARY_MAX_LENGTH);
        String evidenceSummary = truncate(evidence, SUMMARY_MAX_LENGTH);

        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String prompt = promptManager.render(SqlAgentSpec.PromptName.COMPLEXITY_ANALYZER, Map.of(
                    "rewrite_query", rewriteQuery,
                    "schema_summary", schemaSummary,
                    "evidence_summary", evidenceSummary
            ));

            LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                    (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                    SqlAgentSpec.Node.ANALYZER, llmCallReporter);
            String raw = strategy.generateSql(prompt, null);
            String response = raw == null ? "" : raw.trim();

            Map<String, Object> parsed = parseJson(response);
            String complexity = parsed.get("complexity") instanceof String s
                    ? s.toUpperCase() : "SIMPLE";
            String reason = parsed.get("reason") instanceof String s ? s : "";

            if (!"SIMPLE".equals(complexity) && !"COMPLEX".equals(complexity) && !"CLARIFY".equals(complexity)) {
                complexity = "SIMPLE";
            }

            log.info("[AnalyzerNode] complexity={} reason='{}'", complexity, reason);
            out.put(SqlAgentSpec.StateKey.COMPLEXITY, complexity);
            out.put("complexityReason", reason);
        } catch (Exception e) {
            log.warn("[AnalyzerNode] LLM call or parsing failed, defaulting to SIMPLE: {}", e.getMessage(), e);
            out.put(SqlAgentSpec.StateKey.COMPLEXITY, "SIMPLE");
            out.put("complexityReason", "Analyzer error: " + e.getMessage());
        }

        return out;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "无";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...（已截断）";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String response) {
        if (response == null || response.isBlank()) {
            return Map.of();
        }
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```(?:\\w+)?\\s*\\n?", "")
                    .replaceAll("\\n?```\\s*$", "")
                    .trim();
        }
        try {
            return objectMapper.readValue(cleaned, Map.class);
        } catch (Exception e) {
            log.debug("[AnalyzerNode] JSON parse failed: {}", e.getMessage());
            return Map.of();
        }
    }
}
