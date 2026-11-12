package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sql.logic.engine.common.dto.BusinessKnowledgeCreateRequest;
import com.sql.logic.engine.common.dto.BusinessKnowledgeResponse;
import com.sql.logic.engine.common.dto.BusinessKnowledgeUpdateRequest;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.infrastructure.dao.BusinessKnowledgeDao;
import com.sql.logic.engine.infrastructure.po.BusinessKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Phase 5 — CRUD for user-managed business knowledge (glossary + few-shot QA).
 * Rows are written to MySQL as pending, then indexed in pgvector. Failed indexing
 * and deletion remain durable pending work for the retry job. Tenant scoping is enforced by
 * {@code userId} + {@code connectionId} ownership validation.
 */
@Service
public class BusinessKnowledgeAppService {

    private static final Logger log = LoggerFactory.getLogger(BusinessKnowledgeAppService.class);
    private static final Set<String> VALID_VECTOR_TYPES = Set.of(
            SqlAgentSpec.Retrieval.VectorType.GLOSSARY_KNOWLEDGE,
            SqlAgentSpec.Retrieval.VectorType.QUESTION_KNOWLEDGE);

    private final BusinessKnowledgeDao businessKnowledgeDao;
    private final KnowledgeEmbeddingService knowledgeEmbeddingService;
    private final DatabaseAppService databaseAppService;
    private final RedissonClient redissonClient;

    public BusinessKnowledgeAppService(BusinessKnowledgeDao businessKnowledgeDao,
                                       KnowledgeEmbeddingService knowledgeEmbeddingService,
                                       DatabaseAppService databaseAppService,
                                       RedissonClient redissonClient) {
        this.businessKnowledgeDao = businessKnowledgeDao;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.databaseAppService = databaseAppService;
        this.redissonClient = redissonClient;
    }

    public List<BusinessKnowledgeResponse> list(Long userId, Long connectionId) {
        databaseAppService.assertUserCanAccessConnection(userId, connectionId);
        QueryWrapper<BusinessKnowledge> q = new QueryWrapper<>();
        q.eq("user_id", userId).eq("connection_id", connectionId)
                .ne("status", 2).orderByDesc("create_time");
        return businessKnowledgeDao.selectList(q).stream().map(this::toResponse).toList();
    }

    public BusinessKnowledgeResponse create(Long userId, BusinessKnowledgeCreateRequest request) {
        validateCreate(request);
        databaseAppService.assertUserCanAccessConnection(userId, request.getConnectionId());

        BusinessKnowledge row = new BusinessKnowledge();
        row.setUserId(userId);
        row.setConnectionId(request.getConnectionId());
        row.setVectorType(request.getVectorType());
        row.setTerm(request.getTerm());
        row.setDescription(request.getDescription());
        row.setSynonyms(request.getSynonyms());
        row.setQuestion(request.getQuestion());
        row.setAnswer(request.getAnswer());
        row.setStatus(0);
        row.setKnowledgeVersion(1L);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        businessKnowledgeDao.insert(row);

        withLock(row.getId(), () -> embedSoft(row));
        return toResponse(row);
    }

    public BusinessKnowledgeResponse update(Long userId, BusinessKnowledgeUpdateRequest request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException("Knowledge id is required");
        }
        return withLock(request.getId(), () -> {
            BusinessKnowledge row = ownedActiveRow(userId, request.getId());
            databaseAppService.assertUserCanAccessConnection(userId, row.getConnectionId());
            if (request.getVectorType() != null) row.setVectorType(request.getVectorType());
            if (request.getTerm() != null) row.setTerm(request.getTerm());
            if (request.getDescription() != null) row.setDescription(request.getDescription());
            if (request.getSynonyms() != null) row.setSynonyms(request.getSynonyms());
            if (request.getQuestion() != null) row.setQuestion(request.getQuestion());
            if (request.getAnswer() != null) row.setAnswer(request.getAnswer());
            validateContent(row);
            long oldVersion = row.getKnowledgeVersion() == null ? 1 : row.getKnowledgeVersion();
            row.setKnowledgeVersion(oldVersion + 1);
            row.setStatus(0);
            row.setUpdateTime(new Date());
            int changed = businessKnowledgeDao.update(row, new UpdateWrapper<BusinessKnowledge>()
                    .eq("id", row.getId()).eq("knowledge_version", oldVersion).ne("status", 2));
            if (changed != 1) throw new IllegalStateException("Knowledge was updated concurrently");
            embedSoft(row);
            return toResponse(row);
        });
    }

    public void delete(Long userId, Long knowledgeId) {
        withLock(knowledgeId, () -> {
            BusinessKnowledge row = ownedActiveRow(userId, knowledgeId);
            databaseAppService.assertUserCanAccessConnection(userId, row.getConnectionId());
            businessKnowledgeDao.update(null, new UpdateWrapper<BusinessKnowledge>()
                    .eq("id", knowledgeId).set("status", 2).set("update_time", new Date()));
            deletePending(row);
        });
    }

    /** A failed embedding leaves the row pending for the retry job. */
    private void embedSoft(BusinessKnowledge row) {
        try {
            knowledgeEmbeddingService.embedAndUpsert(row);
            int changed = businessKnowledgeDao.update(null, new UpdateWrapper<BusinessKnowledge>()
                    .eq("id", row.getId()).eq("knowledge_version", row.getKnowledgeVersion())
                    .eq("status", 0).set("status", 1));
            if (changed == 1) row.setStatus(1);
        } catch (Exception e) {
            log.error("[BusinessKnowledgeAppService] Embedding failed for row {} (kept, status=0): {}",
                    row.getId(), e.getMessage(), e);
        }
    }

    /** Called by the retry job. The row is re-read under the same distributed lock as edits. */
    public void retryPending(Long rowId) {
        withLock(rowId, () -> {
            BusinessKnowledge row = businessKnowledgeDao.selectById(rowId);
            if (row == null) return;
            if (Integer.valueOf(0).equals(row.getStatus())) embedSoft(row);
            else if (Integer.valueOf(2).equals(row.getStatus())) deletePending(row);
        });
    }

    private void deletePending(BusinessKnowledge row) {
        try {
            knowledgeEmbeddingService.embedAndDelete(row.getId());
            businessKnowledgeDao.delete(new QueryWrapper<BusinessKnowledge>()
                    .eq("id", row.getId()).eq("status", 2));
        } catch (Exception e) {
            log.warn("Knowledge vector deletion pending for {}: {}", row.getId(), e.getMessage());
        }
    }

    private BusinessKnowledge ownedActiveRow(Long userId, Long rowId) {
        BusinessKnowledge row = businessKnowledgeDao.selectById(rowId);
        if (row == null || !userId.equals(row.getUserId()) || Integer.valueOf(2).equals(row.getStatus()))
            throw new IllegalArgumentException("Knowledge not found or does not belong to this user");
        return row;
    }

    private void validateContent(BusinessKnowledge row) {
        if (!VALID_VECTOR_TYPES.contains(row.getVectorType()))
            throw new IllegalArgumentException("Invalid vectorType");
        String text = SqlAgentSpec.Retrieval.VectorType.GLOSSARY_KNOWLEDGE.equals(row.getVectorType())
                ? row.getTerm() : row.getQuestion();
        if (text == null || text.isBlank())
            throw new IllegalArgumentException("Term or question is required for the selected vectorType");
    }

    private void withLock(Long id, Runnable action) { withLock(id, () -> { action.run(); return null; }); }

    private <T> T withLock(Long id, java.util.function.Supplier<T> action) {
        RLock lock = redissonClient.getLock("business-knowledge:" + id);
        try {
            if (!lock.tryLock(5, TimeUnit.SECONDS)) throw new IllegalStateException("Knowledge is busy");
            try { return action.get(); }
            finally { lock.unlock(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while locking knowledge", e);
        }
    }

    private void validateCreate(BusinessKnowledgeCreateRequest request) {
        if (request.getConnectionId() == null) {
            throw new IllegalArgumentException("connectionId is required");
        }
        if (request.getVectorType() == null || !VALID_VECTOR_TYPES.contains(request.getVectorType())) {
            throw new IllegalArgumentException("vectorType must be GLOSSARY_KNOWLEDGE or QUESTION_KNOWLEDGE");
        }
        if (SqlAgentSpec.Retrieval.VectorType.GLOSSARY_KNOWLEDGE.equals(request.getVectorType())) {
            if (request.getTerm() == null || request.getTerm().isBlank()) {
                throw new IllegalArgumentException("Glossary term is required for GLOSSARY_KNOWLEDGE");
            }
        } else {
            if (request.getQuestion() == null || request.getQuestion().isBlank()) {
                throw new IllegalArgumentException("Question is required for QUESTION_KNOWLEDGE");
            }
        }
    }

    private BusinessKnowledgeResponse toResponse(BusinessKnowledge row) {
        BusinessKnowledgeResponse r = new BusinessKnowledgeResponse();
        r.setId(row.getId());
        r.setConnectionId(row.getConnectionId());
        r.setVectorType(row.getVectorType());
        r.setTerm(row.getTerm());
        r.setDescription(row.getDescription());
        r.setSynonyms(row.getSynonyms());
        r.setQuestion(row.getQuestion());
        r.setAnswer(row.getAnswer());
        r.setStatus(row.getStatus());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        r.setCreateTime(row.getCreateTime() != null ? sdf.format(row.getCreateTime()) : null);
        r.setUpdateTime(row.getUpdateTime() != null ? sdf.format(row.getUpdateTime()) : null);
        return r;
    }
}
