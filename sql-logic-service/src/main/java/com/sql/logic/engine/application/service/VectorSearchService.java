package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Phase 5 — four-channel vector retrieval over the pgvector {@link VectorStore},
 * partitioned by {@code userId} + {@code connectionId} + {@code vectorType}.
 * <p>
 * <b>Tenancy safety:</b> every search forces BOTH userId AND connectionId filters
 * (constructed only here, never by callers) so a query for one user/connection can
 * never surface another tenant's knowledge. Channels TABLE/COLUMN are defined for
 * forward-compatibility (a future vector schema-recall node) but not yet wired.
 */
@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    private static final int GLOSSARY_TOP_K = 4;
    private static final int QUESTION_TOP_K = 4;
    private static final int TABLE_TOP_K = 10;
    private static final int COLUMN_TOP_K = 30;

    private final VectorStore vectorStore;

    public VectorSearchService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    private List<Document> search(String query, Long userId, Long connectionId, String vectorType, int topK) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        var filter = b.and(
                b.and(
                        b.eq(SqlAgentSpec.Retrieval.DocumentMetadataKey.VECTOR_TYPE, vectorType),
                        b.eq(SqlAgentSpec.Retrieval.DocumentMetadataKey.USER_ID, String.valueOf(userId))),
                b.eq(SqlAgentSpec.Retrieval.DocumentMetadataKey.CONNECTION_ID, String.valueOf(connectionId))
        ).build();
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .filterExpression(filter)
                .topK(topK)
                .build();
        try {
            List<Document> docs = vectorStore.similaritySearch(request);
            log.debug("[VectorSearchService] {} recall userId={} conn={} topK={} -> {}",
                    vectorType, userId, connectionId, topK, docs == null ? 0 : docs.size());
            return docs == null ? List.of() : docs;
        } catch (Exception e) {
            log.warn("[VectorSearchService] {} recall failed (conn={}): {}", vectorType, connectionId, e.getMessage());
            return List.of();
        }
    }

    public List<Document> recallGlossary(String query, Long userId, Long connectionId) {
        return search(query, userId, connectionId,
                SqlAgentSpec.Retrieval.VectorType.GLOSSARY_KNOWLEDGE, GLOSSARY_TOP_K);
    }

    public List<Document> recallQuestion(String query, Long userId, Long connectionId) {
        return search(query, userId, connectionId,
                SqlAgentSpec.Retrieval.VectorType.QUESTION_KNOWLEDGE, QUESTION_TOP_K);
    }

    /** Reserved for Phase 5.1 (vector schema recall) — defined, not yet wired. */
    public List<Document> recallTable(String query, Long userId, Long connectionId) {
        return search(query, userId, connectionId,
                SqlAgentSpec.Retrieval.VectorType.TABLE, TABLE_TOP_K);
    }

    /** Reserved for Phase 5.1 (vector schema recall) — defined, not yet wired. */
    public List<Document> recallColumn(String query, Long userId, Long connectionId) {
        return search(query, userId, connectionId,
                SqlAgentSpec.Retrieval.VectorType.COLUMN, COLUMN_TOP_K);
    }
}