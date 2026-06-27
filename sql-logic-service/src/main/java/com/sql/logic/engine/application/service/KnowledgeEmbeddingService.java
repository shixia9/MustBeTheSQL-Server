package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.infrastructure.po.BusinessKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 — embeds a {@link BusinessKnowledge} row into the pgvector
 * {@link VectorStore} (idempotent upsert keyed by {@code "k::<rowId>"}) and
 * deletes its vector when the row is removed.
 * <p>
 * Metadata partitioning ({@code userId} + {@code connectionId} + {@code vectorType}
 * + {@code knowledgeId}) lets {@link VectorSearchService} filter per-tenant per-channel.
 * <p>
 * Mirrors the reference project's {@code toDocument()} + {@code DatasetEmbeddingTest}
 * chunked-10 batched embedding pattern, but scoped to a multi-tenant user/connection.
 */
@Service
public class KnowledgeEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEmbeddingService.class);

    /** Deterministic UUID for idempotent upsert — PgVectorStore requires a UUID-compatible id. */
    public static String docId(Long rowId) {
        return UUID.nameUUIDFromBytes(("k::" + rowId).getBytes()).toString();
    }

    private final VectorStore vectorStore;

    public KnowledgeEmbeddingService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Build the embedding Document text for a knowledge row, per vectorType.
     * Mirrors the reference {@code toDocument()} content shapes.
     */
    private String toEmbeddingText(BusinessKnowledge row) {
        if (SqlAgentSpec.Retrieval.VectorType.QUESTION_KNOWLEDGE.equals(row.getVectorType())) {
            return row.getQuestion() == null ? "" : row.getQuestion();
        }
        // glossary: "业务名词: <term>, 说明: <description>, 同义词: <synonyms>"
        StringBuilder sb = new StringBuilder();
        if (row.getTerm() != null) {
            sb.append("业务名词: ").append(row.getTerm());
        }
        if (row.getDescription() != null) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append("说明: ").append(row.getDescription());
        }
        if (row.getSynonyms() != null && !row.getSynonyms().isBlank()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append("同义词: ").append(row.getSynonyms());
        }
        return sb.toString();
    }

    private Map<String, Object> metadataOf(BusinessKnowledge row) {
        return Map.of(
                SqlAgentSpec.Retrieval.DocumentMetadataKey.VECTOR_TYPE, row.getVectorType(),
                SqlAgentSpec.Retrieval.DocumentMetadataKey.USER_ID, String.valueOf(row.getUserId()),
                SqlAgentSpec.Retrieval.DocumentMetadataKey.CONNECTION_ID, String.valueOf(row.getConnectionId()),
                SqlAgentSpec.Retrieval.DocumentMetadataKey.KNOWLEDGE_ID, String.valueOf(row.getId())
        );
    }

    /**
     * Upsert (replace) the vector for {@code row}. First delete the existing doc
     * (idempotent) then add fresh — {@code vectorStore.add} does not upsert by id,
     * so the delete-then-add keeps the vector_store row count flat across edits.
     */
    public void embedAndUpsert(BusinessKnowledge row) {
        String id = docId(row.getId());
        String text = toEmbeddingText(row);
        if (text.isBlank()) {
            log.warn("[KnowledgeEmbeddingService] Skipped empty embedding text for row {} (vectorType={})",
                    row.getId(), row.getVectorType());
            return;
        }
        try {
            vectorStore.delete(List.of(id));
        } catch (Exception e) {
            // best-effort: row may not exist yet on first save
            log.debug("[KnowledgeEmbeddingService] Pre-delete for {} ignored: {}", id, e.getMessage());
        }
        Document doc = new Document(id, text, metadataOf(row));
        vectorStore.add(List.of(doc));
        log.info("[KnowledgeEmbeddingService] Embedded row {} (vectorType={}, conn={})",
                row.getId(), row.getVectorType(), row.getConnectionId());
    }

    /** Remove the vector for a knowledge row. */
    public void embedAndDelete(Long rowId) {
        try {
            vectorStore.delete(List.of(docId(rowId)));
            log.info("[KnowledgeEmbeddingService] Deleted vector for row {}", rowId);
        } catch (Exception e) {
            log.warn("[KnowledgeEmbeddingService] Delete for row {} failed: {}", rowId, e.getMessage());
        }
    }

    /**
     * Batched embed for bulk seeding (future BIRD import). Chunked to 10 per batch
     * to respect embedding API throughput, per the reference {@code DatasetEmbeddingTest}.
     */
    public void embedBatch(List<BusinessKnowledge> rows) {
        if (rows == null || rows.isEmpty()) return;
        int batchSize = 10;
        for (int i = 0; i < rows.size(); i += batchSize) {
            List<BusinessKnowledge> chunk = rows.subList(i, Math.min(i + batchSize, rows.size()));
            List<Document> docs = chunk.stream()
                    .filter(r -> !toEmbeddingText(r).isBlank())
                    .map(r -> new Document(docId(r.getId()), toEmbeddingText(r), metadataOf(r)))
                    .toList();
            if (!docs.isEmpty()) {
                vectorStore.add(docs);
                log.info("[KnowledgeEmbeddingService] Embedded batch of {}", docs.size());
            }
        }
    }
}