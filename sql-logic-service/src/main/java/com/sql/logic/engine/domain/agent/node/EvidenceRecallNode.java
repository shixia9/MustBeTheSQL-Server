package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.application.service.VectorSearchService;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.EvidenceQueryRewriteDTO;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.infrastructure.dao.BusinessKnowledgeDao;
import com.sql.logic.engine.infrastructure.po.BusinessKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
 *   <li>{@code EVIDENCE} — the rendered {@code evidence-glossary} + {@code evidence-knowledge}
 *       prompt text (a plain string, exactly what {@code SchemaLinkingNode} feeds into
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
    private final VectorSearchService vectorSearchService;
    private final BusinessKnowledgeDao businessKnowledgeDao;
    private final LlmCallReporter llmCallReporter;

    public EvidenceRecallNode(LlmClientManager llmClientManager, PromptManager promptManager,
                               VectorSearchService vectorSearchService,
                               BusinessKnowledgeDao businessKnowledgeDao,
                               LlmCallReporter llmCallReporter) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.vectorSearchService = vectorSearchService;
        this.businessKnowledgeDao = businessKnowledgeDao;
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

        // ---- 1. Query rewrite (unchanged from Phase 1) ----
        BeanOutputConverter<EvidenceQueryRewriteDTO> converter =
                new BeanOutputConverter<>(EvidenceQueryRewriteDTO.class);
        String prompt = promptManager.render(SqlAgentSpec.PromptName.EVIDENCE_QUERY_REWRITE, Map.of(
                "latest_query", userInput,
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
            List<Document> glossaryDocs = vectorSearchService.recallGlossary(rewriteQuery, userId, connectionId);
            List<Document> questionDocs = vectorSearchService.recallQuestion(rewriteQuery, userId, connectionId);

            // Glossary text: just the embedded doc text joined.
            String glossaryText = glossaryDocs.stream()
                    .map(d -> d.getText() == null ? "" : d.getText())
                    .reduce("", (a, b) -> a.isBlank() ? b : a + "\n" + b);

            // FAQ: look up full Q/A rows via knowledgeId metadata (doc only stores the question text).
            List<Map<String, Object>> evidenceFaq = new ArrayList<>();
            List<Long> faqIds = questionDocs.stream()
                    .map(this::readKnowledgeId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            String faqText;
            if (!faqIds.isEmpty()) {
                List<BusinessKnowledge> rows = businessKnowledgeDao.selectBatchIds(faqIds);
                StringBuilder sb = new StringBuilder();
                for (BusinessKnowledge row : rows) {
                    sb.append("来源: Q: ").append(safe(row.getQuestion()))
                      .append(" A: ").append(safe(row.getAnswer())).append("\n");
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("question", row.getQuestion());
                    entry.put("answer", row.getAnswer());
                    entry.put("score", scoreForId(questionDocs, row.getId()));
                    evidenceFaq.add(entry);
                }
                faqText = sb.toString().trim();
            } else {
                faqText = "";
            }

            // Structured glossary entries (term + description + score).
            List<Map<String, Object>> evidenceGlossary = new ArrayList<>();
            List<Long> glossaryIds = glossaryDocs.stream()
                    .map(this::readKnowledgeId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            if (!glossaryIds.isEmpty()) {
                List<BusinessKnowledge> rows = businessKnowledgeDao.selectBatchIds(glossaryIds);
                for (BusinessKnowledge row : rows) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("term", row.getTerm());
                    entry.put("description", row.getDescription());
                    entry.put("synonyms", row.getSynonyms());
                    entry.put("score", scoreForId(glossaryDocs, row.getId()));
                    evidenceGlossary.add(entry);
                }
            }

            // ---- 3. Render the evidence prompt text (the {evidence} slot for SQL gen) ----
            String glossaryPrompt = promptManager.render(SqlAgentSpec.PromptName.EVIDENCE_GLOSSARY,
                    Map.of("businessKnowledge", glossaryText.isBlank() ? "无" : glossaryText));
            String knowledgePrompt = promptManager.render(SqlAgentSpec.PromptName.EVIDENCE_KNOWLEDGE,
                    Map.of("agentKnowledge", faqText.isBlank() ? "无" : faqText));

            boolean empty = glossaryText.isBlank() && faqText.isBlank();
            String evidence = empty ? "无" : (glossaryPrompt + "\n" + knowledgePrompt);
            result.put(SqlAgentSpec.StateKey.EVIDENCE, evidence);
            result.put(SqlAgentSpec.StateKey.EVIDENCE_GLOSSARY, evidenceGlossary);
            result.put(SqlAgentSpec.StateKey.EVIDENCE_FAQ, evidenceFaq);
            log.info("[EvidenceRecallNode] RAG recalls: glossary={}, faq={}",
                    evidenceGlossary.size(), evidenceFaq.size());
        } catch (Exception e) {
            log.warn("[EvidenceRecallNode] RAG retrieval failed, degrading to no-evidence: {}", e.getMessage(), e);
            result.put(SqlAgentSpec.StateKey.EVIDENCE, "无");
            result.put(SqlAgentSpec.StateKey.EVIDENCE_GLOSSARY, List.of());
            result.put(SqlAgentSpec.StateKey.EVIDENCE_FAQ, List.of());
        }

        return result;
    }

    /** Read the deterministic knowledgeId metadata placed by KnowledgeEmbeddingService. */
    private Long readKnowledgeId(Document doc) {
        Object v = doc.getMetadata() == null ? null
                : doc.getMetadata().get(SqlAgentSpec.Retrieval.DocumentMetadataKey.KNOWLEDGE_ID);
        if (v == null) return null;
        try {
            return Long.valueOf(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Find the similarity score of the doc whose knowledgeId == rowId (if present). */
    private double scoreForId(List<Document> docs, Long rowId) {
        String target = String.valueOf(rowId);
        for (Document d : docs) {
            Object v = d.getMetadata() == null ? null
                    : d.getMetadata().get(SqlAgentSpec.Retrieval.DocumentMetadataKey.KNOWLEDGE_ID);
            if (v != null && target.equals(String.valueOf(v))) {
                Double s = d.getScore();
            return s == null ? 0.0 : s;
            }
        }
        return 0.0;
    }

    private String safe(String s) {
        return s == null ? "" : s;
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