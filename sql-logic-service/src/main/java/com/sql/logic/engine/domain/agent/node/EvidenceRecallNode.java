package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.EvidenceQueryRewriteDTO;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Evidence Recall Node — Phase 1 version.
 * <p>
 * Rewrites the user's natural language query into a standalone, precise
 * query suitable for SQL generation. Uses LLM via PromptManager template.
 * <p>
 * Uses BeanOutputConverter for structured JSON output, replacing the previous
 * fragile manual string parsing of {"standalone_query": "..."}.
 * <p>
 * In Phase 5 (RAG), this node will also perform vector similarity search
 * to enrich context with business glossary terms and historical Q&A.
 */
@Component
public class EvidenceRecallNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRecallNode.class);

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

        // Use BeanOutputConverter to enforce structured JSON output from the LLM
        BeanOutputConverter<EvidenceQueryRewriteDTO> converter =
                new BeanOutputConverter<>(EvidenceQueryRewriteDTO.class);

        // Render the query rewrite prompt with the format specification injected
        String prompt = promptManager.render(SqlAgentSpec.PromptName.EVIDENCE_QUERY_REWRITE, Map.of(
                "latest_query", userInput,
                "format", converter.getFormat()  // Inject JSON schema format into the template
        ));

        // Resolve LLM strategy — falls back to user default, then system default
        LLMStrategy strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
        String rewriteResponse = strategy.generateSql(prompt, null);

        // Parse the LLM response into a typed DTO
        String rewriteQuery = extractQuery(rewriteResponse.trim(), converter);

        log.info("[EvidenceRecallNode] Input: {}, Rewritten: {}", userInput, rewriteQuery);

        return Map.of(
                SqlAgentSpec.StateKey.REWRITE_QUERY, rewriteQuery,
                SqlAgentSpec.StateKey.EVIDENCE, ""  // Phase 1: no RAG evidence yet
        );
    }

    /**
     * Extract the standalone query from the LLM response.
     * <p>
     * First attempts structured JSON parsing via BeanOutputConverter.
     * Falls back to plain text extraction if JSON parsing fails
     * (e.g., when the LLM wraps output in markdown code fences).
     */
    private String extractQuery(String response, BeanOutputConverter<EvidenceQueryRewriteDTO> converter) {
        if (response == null || response.isBlank()) {
            return "";
        }

        // Strip markdown code fences if present (LLMs often wrap JSON in ```json ... ```)
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```(?:\\w+)?\\s*\\n?", "")
                    .replaceAll("\\n?```\\s*$", "")
                    .trim();
        }

        // Try structured JSON parsing first
        try {
            EvidenceQueryRewriteDTO dto = converter.convert(cleaned);
            if (dto != null && dto.standalone_query() != null && !dto.standalone_query().isBlank()) {
                return dto.standalone_query();
            }
        } catch (Exception e) {
            log.debug("[EvidenceRecallNode] BeanOutputConverter parsing failed, falling back to plain text: {}", e.getMessage());
        }

        // Fallback: return the cleaned text as-is (LLM may have just output the query without JSON wrapping)
        return cleaned;
    }
}