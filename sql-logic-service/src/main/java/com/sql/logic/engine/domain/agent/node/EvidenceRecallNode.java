package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Evidence Recall Node — Phase 1 version.
 * <p>
 * Rewrites the user's natural language query into a standalone, precise
 * query suitable for SQL generation. Uses LLM via PromptManager template.
 * <p>
 * In Phase 5 (RAG), this node will also perform vector similarity search
 * to enrich context with business glossary terms and historical Q&A.
 */
@Component
public class EvidenceRecallNode implements NodeAction {

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;

    public EvidenceRecallNode(LlmClientManager llmClientManager, PromptManager promptManager) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String userInput = state.value(SqlAgentSpec.StateKey.INPUT, "");
        Object llmConfigIdObj = state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, null);
        Long llmConfigId = llmConfigIdObj instanceof Long ? (Long) llmConfigIdObj : null;
        Object userIdObj = state.value(SqlAgentSpec.StateKey.USER_ID, null);
        Long userId = userIdObj instanceof Long ? (Long) userIdObj : null;

        // Render the query rewrite prompt
        String prompt = promptManager.render(SqlAgentSpec.PromptName.EVIDENCE_QUERY_REWRITE, Map.of(
                "latest_query", userInput,
                "format", ""  // Phase 1: simplified, no structured output format
        ));

        // Resolve LLM strategy — falls back to user default, then system default
        LLMStrategy strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
        String rewriteResponse = strategy.generateSql(prompt, null);

        // Extract the rewritten query from the response
        // The LLM should output a JSON like {"standalone_query": "..."} but we'll also
        // handle plain text fallback
        String rewriteQuery = extractQuery(rewriteResponse.trim());

        System.out.println("[EvidenceRecallNode] Input: " + userInput);
        System.out.println("[EvidenceRecallNode] Rewritten: " + rewriteQuery);

        return Map.of(
                SqlAgentSpec.StateKey.REWRITE_QUERY, rewriteQuery,
                SqlAgentSpec.StateKey.EVIDENCE, ""  // Phase 1: no RAG evidence yet
        );
    }

    /**
     * Extract the standalone query from the LLM response.
     * Handles both JSON format {"standalone_query": "..."} and plain text.
     */
    private String extractQuery(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        String trimmed = response.trim();

        // Try JSON format first
        if (trimmed.contains("\"standalone_query\"")) {
            try {
                // Simple extraction without full JSON parsing
                int idx = trimmed.indexOf("\"standalone_query\"");
                int colonIdx = trimmed.indexOf(":", idx);
                int quoteStart = trimmed.indexOf("\"", colonIdx + 1);
                int quoteEnd = trimmed.indexOf("\"", quoteStart + 1);
                if (quoteStart >= 0 && quoteEnd > quoteStart) {
                    return trimmed.substring(quoteStart + 1, quoteEnd);
                }
            } catch (Exception e) {
                // Fall through to plain text
            }
        }

        // Strip markdown code fences if present
        String cleaned = trimmed;
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```(?:\\w+)?\\s*\\n?", "")
                    .replaceAll("\\n?```\\s*$", "")
                    .trim();
        }

        return cleaned;
    }
}