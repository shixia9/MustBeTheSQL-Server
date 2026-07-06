package com.sql.logic.engine.domain.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.application.service.KnowledgeEmbeddingService;
import com.sql.logic.engine.infrastructure.dao.MemoryItemDao;
import com.sql.logic.engine.infrastructure.po.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MemoryDomainService {

    private static final Logger log = LoggerFactory.getLogger(MemoryDomainService.class);

    private static final String MEMORY_DOC_ID_PREFIX = "mem::";
    private static final String VECTOR_TYPE_MEMORY = "MEMORY";

    private final MemoryItemDao memoryItemDao;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;
    private final VectorStore vectorStore;

    public MemoryDomainService(MemoryItemDao memoryItemDao,
                               KnowledgeEmbeddingService knowledgeEmbeddingService,
                               VectorStore vectorStore) {
        this.memoryItemDao = memoryItemDao;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.vectorStore = vectorStore;
    }

    public int saveMemories(Long userId, Long workspaceId, String threadId,
                            List<CandidateMemory> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (CandidateMemory candidate : candidates) {
            try {
                String normalized = normalizeContent(candidate.getText());
                String dedupeHash = sha256Hex(normalized);

                QueryWrapper<MemoryItem> query = new QueryWrapper<>();
                query.eq("user_id", userId);
                query.eq("dedupe_hash", dedupeHash);
                MemoryItem existing = memoryItemDao.selectOne(query);

                MemoryItem item;
                if (existing == null) {
                    item = new MemoryItem();
                    item.setUserId(userId);
                    item.setWorkspaceId(workspaceId);
                    item.setType(candidate.getType());
                    item.setContent(candidate.getText());
                    item.setImportance(BigDecimal.valueOf(candidate.getImportance()));
                    item.setTags(candidate.getTags());
                    item.setSourceSessionId(threadId);
                    item.setDedupeHash(dedupeHash);
                    item.setStatus(1);
                    item.setCreateTime(LocalDateTime.now());
                    item.setUpdateTime(LocalDateTime.now());
                    memoryItemDao.insert(item);
                } else {
                    item = existing;
                    BigDecimal oldImportance = item.getImportance();
                    BigDecimal newImportance = BigDecimal.valueOf(candidate.getImportance());
                    if (oldImportance == null || newImportance.compareTo(oldImportance) > 0) {
                        item.setImportance(newImportance);
                    }
                    if (candidate.getTags() != null) {
                        Set<String> merged = new HashSet<>();
                        if (item.getTags() != null) {
                            merged.addAll(item.getTags());
                        }
                        merged.addAll(candidate.getTags());
                        item.setTags(new ArrayList<>(merged));
                    }
                    item.setUpdateTime(LocalDateTime.now());
                    memoryItemDao.updateById(item);
                }

                embedMemory(item);

                saved++;
            } catch (Exception e) {
                log.warn("[MemoryDomainService] Failed to save candidate memory (type={}): {}",
                        candidate.getType(), e.getMessage());
            }
        }
        return saved;
    }

    public List<Map<String, Object>> searchRelevant(Long userId, String query, int topK) {
        if (userId == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            FilterExpressionBuilder fb = new FilterExpressionBuilder();
            var filter = fb.and(
                    fb.eq("userId", String.valueOf(userId)),
                    fb.eq("vectorType", VECTOR_TYPE_MEMORY)
            ).build();
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .filterExpression(filter)
                    .topK(topK * 3)
                    .build();
            List<Document> docs = vectorStore.similaritySearch(request);

            List<Map<String, Object>> results = new ArrayList<>();
            for (Document doc : docs) {
                try {
                    Object idObj = doc.getMetadata() != null
                            ? doc.getMetadata().get("memoryItemId") : null;
                    if (idObj == null) {
                        continue;
                    }
                    Long memoryItemId = Long.valueOf(String.valueOf(idObj));
                    MemoryItem item = memoryItemDao.selectById(memoryItemId);
                    if (item == null || item.getStatus() == null || item.getStatus() != 1) {
                        continue;
                    }
                    double score = doc.getScore() == null ? 0.0 : doc.getScore();
                    double importance = item.getImportance() == null
                            ? 0.0 : item.getImportance().doubleValue();
                    double combined = 0.7 * score + 0.3 * importance;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", item.getId());
                    entry.put("type", item.getType());
                    entry.put("content", item.getContent());
                    entry.put("importance", importance);
                    entry.put("score", combined);
                    results.add(entry);
                } catch (Exception e) {
                    log.debug("[MemoryDomainService] Skipping vector doc: {}", e.getMessage());
                }
            }

            results.sort((a, b) -> {
                double s1 = ((Number) a.get("score")).doubleValue();
                double s2 = ((Number) b.get("score")).doubleValue();
                return Double.compare(s2, s1);
            });

            return results.size() > topK ? results.subList(0, topK) : results;
        } catch (Exception e) {
            log.warn("[MemoryDomainService] Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private void embedMemory(MemoryItem item) {
        if (item.getContent() == null || item.getContent().isBlank()) {
            return;
        }
        try {
            String docId = UUID.nameUUIDFromBytes(
                    (MEMORY_DOC_ID_PREFIX + item.getId()).getBytes()).toString();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("userId", String.valueOf(item.getUserId()));
            metadata.put("memoryItemId", String.valueOf(item.getId()));
            metadata.put("vectorType", VECTOR_TYPE_MEMORY);
            metadata.put("memoryType", item.getType());

            try {
                vectorStore.delete(List.of(docId));
            } catch (Exception ignored) {
            }

            Document doc = new Document(docId, item.getContent(), metadata);
            vectorStore.add(List.of(doc));
            log.debug("[MemoryDomainService] Embedded memory item {}", item.getId());
        } catch (Exception e) {
            log.warn("[MemoryDomainService] Embedding failed for memory {}: {}", item.getId(), e.getMessage());
        }
    }

    private String normalizeContent(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 hashing failed", e);
        }
    }
}
