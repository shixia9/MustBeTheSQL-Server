package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.application.service.RagEvidenceService;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.EvidenceQueryRewriteDTO;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evidence Recall Node — Phase 5 (RAG) version.
 * <p>
 * Phase 1 rewrote the user's query into a standalone one and wrote {@code EVIDENCE=""}.
 * Phase 5 keeps that rewrite, then performs two-channel vector retrieval
 * (glossary + few-shot Q/A) over the pgvector store — partitioned by the user's
 * {@code userId} + {@code connectionId} — and composes the recalls into:
 * <ul>
 *   <li>{@code EVIDENCE} — validated glossary and Q/A prompt text
 *       (a plain string, exactly what {@code SchemaLinkingNode} feeds into
 *       {@code mix-selector.st} as {@code {evidence}} and {@code SqlGenerationNode} feeds
 *       into {@code new-sql-generate.st}). Backward-compatible string contract.</li>
 *   <li>{@code EVIDENCE_GLOSSARY} / {@code EVIDENCE_FAQ} — structured lists for the
 *       frontend card only.</li>
 * </ul>
 * Failures in retrieval/embedding are caught and degrade gracefully to
 * {@code EVIDENCE="无"} + empty structured arrays so the main chain never blocks.
 */
@Component
public class EvidenceRecallNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(EvidenceRecallNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final RagEvidenceService ragEvidenceService;
    private final LlmCallReporter llmCallReporter;

    public EvidenceRecallNode(LlmClientManager llmClientManager, PromptManager promptManager,
                               RagEvidenceService ragEvidenceService,
                               LlmCallReporter llmCallReporter) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.ragEvidenceService = ragEvidenceService;
        this.llmCallReporter = llmCallReporter;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String userInput = state.value(SqlAgentSpec.StateKey.INPUT, "");
        Long llmConfigId = AgentStateUtil.toLong(
                state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(
                state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));
        Long connectionId = AgentStateUtil.toLong(
                state.value(SqlAgentSpec.StateKey.CONNECTION_ID, (Long) null));

        // ---- 1. Query rewrite ----
        String conversationHistory = state.value(SqlAgentSpec.StateKey.CONVERSATION_HISTORY, "");
        BeanOutputConverter<EvidenceQueryRewriteDTO> converter =
                new BeanOutputConverter<>(EvidenceQueryRewriteDTO.class);
        String prompt = promptManager.render(SqlAgentSpec.PromptName.EVIDENCE_QUERY_REWRITE, Map.of(
                "latest_query", userInput,
                "conversation_history", conversationHistory == null || conversationHistory.isBlank() ? "" : conversationHistory,
                "format", converter.getFormat()
        ));
        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.EVIDENCE_RECALL, llmCallReporter);
        String rewriteResponse = strategy.generateSql(prompt, null);
        String rewriteQuery = extractQuery(rewriteResponse == null ? "" : rewriteResponse.trim(), converter);
        log.info("[EvidenceRecallNode] Input: {}, Rewritten: {}", userInput, rewriteQuery);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(SqlAgentSpec.StateKey.REWRITE_QUERY, rewriteQuery);

        // ---- 2. RAG retrieval (graceful on failure) ----
        if (userId == null || connectionId == null || rewriteQuery == null || rewriteQuery.isBlank()) {
            log.info("[EvidenceRecallNode] Skipping RAG (userId/connectionId/rewriteQuery missing)");
            result.put(SqlAgentSpec.StateKey.EVIDENCE, "无");
            result.put(SqlAgentSpec.StateKey.EVIDENCE_GLOSSARY, List.of());
            result.put(SqlAgentSpec.StateKey.EVIDENCE_FAQ, List.of());
            return result;
        }

        try {
            RagEvidenceService.Evidence evidence = ragEvidenceService.recall(
                    rewriteQuery, userId, connectionId, 4, 0);
            result.put(SqlAgentSpec.StateKey.EVIDENCE, evidence.text());
            result.put(SqlAgentSpec.StateKey.EVIDENCE_GLOSSARY, evidence.glossary());
            result.put(SqlAgentSpec.StateKey.EVIDENCE_FAQ, evidence.faq());
            log.info("[EvidenceRecallNode] RAG recalls: glossary={}, faq={}",
                    evidence.glossary().size(), evidence.faq().size());
        } catch (Exception e) {
            log.warn("[EvidenceRecallNode] RAG retrieval failed, degrading to no-evidence: {}", e.getMessage(), e);
            result.put(SqlAgentSpec.StateKey.EVIDENCE, "无");
            result.put(SqlAgentSpec.StateKey.EVIDENCE_GLOSSARY, List.of());
            result.put(SqlAgentSpec.StateKey.EVIDENCE_FAQ, List.of());
        }

        return result;
    }

    /**
     * Extract the standalone query from the LLM response.
     * BeanOutputConverter first; plain-text fallback when the LLM omits JSON wrapping.
     */
    private String extractQuery(String response, BeanOutputConverter<EvidenceQueryRewriteDTO> converter) {
        if (response == null || response.isBlank()) {
            return "";
        }
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```(?:\\w+)?\\s*\\n?", "")
                    .replaceAll("\\n?```\\s*$", "")
                    .trim();
        }
        try {
            EvidenceQueryRewriteDTO dto = converter.convert(cleaned);
            if (dto != null && dto.standalone_query() != null && !dto.standalone_query().isBlank()) {
                return dto.standalone_query();
            }
        } catch (Exception e) {
            log.debug("[EvidenceRecallNode] BeanOutputConverter parsing failed, plain-text fallback: {}", e.getMessage());
        }
        return cleaned;
    }
}
