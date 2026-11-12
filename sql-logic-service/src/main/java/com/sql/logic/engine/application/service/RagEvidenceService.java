package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.dto.EvidenceQueryRewriteDTO;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.infrastructure.dao.BusinessKnowledgeDao;
import com.sql.logic.engine.infrastructure.po.BusinessKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request-scoped RAG assembly. Vector metadata is only a candidate; MySQL is authoritative. */
@Service
public class RagEvidenceService {
    private static final Logger log = LoggerFactory.getLogger(RagEvidenceService.class);
    private static final int MAX_EVIDENCE_CHARS = 6000;
    private static final int MAX_ENTRY_CHARS = 1000;
    private static final int MAX_SUMMARY_FIELD_CHARS = 300;
    private final VectorSearchService searchService;
    private final BusinessKnowledgeDao knowledgeDao;
    private final PromptManager promptManager;
    private final LlmClientManager llmClientManager;

    public record Evidence(String text, List<Map<String, Object>> glossary,
                           List<Map<String, Object>> faq) {
        public static Evidence empty() { return new Evidence("无", List.of(), List.of()); }
    }

    public RagEvidenceService(VectorSearchService searchService, BusinessKnowledgeDao knowledgeDao,
                              PromptManager promptManager, LlmClientManager llmClientManager) {
        this.searchService = searchService;
        this.knowledgeDao = knowledgeDao;
        this.promptManager = promptManager;
        this.llmClientManager = llmClientManager;
    }

    /** Rewrite only follow-up questions; any LLM or format failure retains the original. */
    public String rewriteForHistory(String query, String history, Long llmConfigId, Long userId) {
        if (query == null || history == null || history.isBlank()) return query;
        try {
            BeanOutputConverter<EvidenceQueryRewriteDTO> converter =
                    new BeanOutputConverter<>(EvidenceQueryRewriteDTO.class);
            String prompt = promptManager.render(SqlAgentSpec.PromptName.EVIDENCE_QUERY_REWRITE,
                    Map.of("latest_query", query, "conversation_history", history,
                            "format", converter.getFormat()));
            var strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
            if (strategy == null) return query;
            String response = strategy.generateSql(prompt, null);
            if (response == null || response.isBlank()) return query;
            String cleaned = response.trim().replaceAll("(?s)^```(?:\\w+)?\\s*", "")
                    .replaceAll("(?s)\\s*```$", "").trim();
            EvidenceQueryRewriteDTO dto = converter.convert(cleaned);
            return dto != null && dto.standalone_query() != null && !dto.standalone_query().isBlank()
                    ? dto.standalone_query() : query;
        } catch (Exception e) {
            log.debug("RAG query rewrite failed, using original query: {}", e.getMessage());
            return query;
        }
    }

    public Evidence recall(String query, Long userId, Long connectionId, int topK, double threshold) {
        if (query == null || query.isBlank() || userId == null || connectionId == null) return Evidence.empty();
        int limit = Math.max(1, Math.min(topK, 10));
        double minScore = Math.max(0, Math.min(threshold, 1));
        try {
            List<Map<String, Object>> glossary = resolve(
                    searchService.recallGlossary(query, userId, connectionId, limit), userId, connectionId,
                    SqlAgentSpec.Retrieval.VectorType.GLOSSARY_KNOWLEDGE, minScore);
            List<Map<String, Object>> faq = resolve(
                    searchService.recallQuestion(query, userId, connectionId, limit), userId, connectionId,
                    SqlAgentSpec.Retrieval.VectorType.QUESTION_KNOWLEDGE, minScore);
            StringBuilder text = new StringBuilder();
            append(text, "业务术语", glossary, false);
            append(text, "参考问答", faq, true);
            return new Evidence(text.isEmpty() ? "无" : text.toString(), compact(glossary), compact(faq));
        } catch (Exception e) {
            log.warn("RAG recall failed for connection {}: {}", connectionId, e.getMessage());
            return Evidence.empty();
        }
    }

    private List<Map<String, Object>> resolve(List<Document> docs, Long userId, Long connectionId,
                                               String type, double threshold) {
        if (docs == null || docs.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Document doc : docs) {
            double score = doc.getScore() == null ? 0 : doc.getScore();
            if (score < threshold) continue;
            Map<String, Object> metadata = doc.getMetadata();
            if (metadata == null) continue;
            Long id = number(metadata.get(SqlAgentSpec.Retrieval.DocumentMetadataKey.KNOWLEDGE_ID));
            if (id == null || !seen.add(id)) continue;
            BusinessKnowledge row = knowledgeDao.selectById(id);
            if (row == null || !userId.equals(row.getUserId()) || !connectionId.equals(row.getConnectionId())
                    || !type.equals(row.getVectorType()) || !Integer.valueOf(1).equals(row.getStatus())) continue;
            Long indexedVersion = number(metadata.get(SqlAgentSpec.Retrieval.DocumentMetadataKey.KNOWLEDGE_VERSION));
            long currentVersion = row.getKnowledgeVersion() == null ? 1 : row.getKnowledgeVersion();
            // Existing documents have no version. They remain usable until their first update/re-index.
            if ((indexedVersion == null ? 1 : indexedVersion) != currentVersion) continue;
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("knowledgeId", id);
            hit.put("score", score);
            if (SqlAgentSpec.Retrieval.VectorType.QUESTION_KNOWLEDGE.equals(type)) {
                hit.put("question", brief(row.getQuestion()));
                hit.put("answer", brief(row.getAnswer()));
            } else {
                hit.put("term", brief(row.getTerm()));
                hit.put("description", brief(row.getDescription()));
                hit.put("synonyms", brief(row.getSynonyms()));
            }
            result.add(hit);
        }
        return result;
    }

    private static void append(StringBuilder out, String heading, List<Map<String, Object>> hits, boolean faq) {
        if (hits.isEmpty() || out.length() >= MAX_EVIDENCE_CHARS) return;
        out.append("### ").append(heading).append('\n');
        for (Map<String, Object> hit : hits) {
            String line = faq
                    ? "Q: " + hit.get("question") + " A: " + hit.get("answer")
                    : "术语: " + hit.get("term") + " 说明: " + hit.get("description")
                      + " 同义词: " + hit.get("synonyms");
            if (line.length() > MAX_ENTRY_CHARS) line = line.substring(0, MAX_ENTRY_CHARS);
            if (out.length() + line.length() + 1 > MAX_EVIDENCE_CHARS) break;
            out.append(line).append('\n');
        }
    }

    private static Long number(Object value) {
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String brief(String value) {
        String s = safe(value);
        return s.length() <= MAX_ENTRY_CHARS ? s : s.substring(0, MAX_ENTRY_CHARS);
    }

    private static List<Map<String, Object>> compact(List<Map<String, Object>> hits) {
        return hits.stream().map(hit -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            hit.forEach((key, value) -> {
                if (value instanceof String s && s.length() > MAX_SUMMARY_FIELD_CHARS)
                    summary.put(key, s.substring(0, MAX_SUMMARY_FIELD_CHARS));
                else summary.put(key, value);
            });
            return summary;
        }).toList();
    }
}
