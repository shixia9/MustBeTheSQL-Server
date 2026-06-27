package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.common.dto.BusinessKnowledgeCreateRequest;
import com.sql.logic.engine.common.dto.BusinessKnowledgeResponse;
import com.sql.logic.engine.common.dto.BusinessKnowledgeUpdateRequest;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.infrastructure.dao.BusinessKnowledgeDao;
import com.sql.logic.engine.infrastructure.po.BusinessKnowledge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Phase 5 — CRUD for user-managed business knowledge (glossary + few-shot QA).
 * Rows are written to MySQL, then embedded into pgvector on save (save-then-embed),
 * and their vector is deleted on remove. Embedding failure does NOT roll back the
 * MySQL row — the row is kept with {@code status=0} ("pending re-embed") so a future
 * backfill can retry. Tenant scoping is enforced on every read/write by
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

    public BusinessKnowledgeAppService(BusinessKnowledgeDao businessKnowledgeDao,
                                       KnowledgeEmbeddingService knowledgeEmbeddingService,
                                       DatabaseAppService databaseAppService) {
        this.businessKnowledgeDao = businessKnowledgeDao;
        this.knowledgeEmbeddingService = knowledgeEmbeddingService;
        this.databaseAppService = databaseAppService;
    }

    public List<BusinessKnowledgeResponse> list(Long userId, Long connectionId) {
        databaseAppService.assertUserCanAccessConnection(userId, connectionId);
        QueryWrapper<BusinessKnowledge> q = new QueryWrapper<>();
        q.eq("user_id", userId).eq("connection_id", connectionId).orderByDesc("create_time");
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
        row.setStatus(1);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        businessKnowledgeDao.insert(row);

        embedSoft(row);
        return toResponse(row);
    }

    public BusinessKnowledgeResponse update(Long userId, BusinessKnowledgeUpdateRequest request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException("Knowledge id is required");
        }
        BusinessKnowledge row = businessKnowledgeDao.selectById(request.getId());
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Knowledge not found or does not belong to this user");
        }
        databaseAppService.assertUserCanAccessConnection(userId, row.getConnectionId());

        if (request.getVectorType() != null) {
            if (!VALID_VECTOR_TYPES.contains(request.getVectorType())) {
                throw new IllegalArgumentException("Invalid vectorType");
            }
            row.setVectorType(request.getVectorType());
        }
        if (request.getTerm() != null) row.setTerm(request.getTerm());
        if (request.getDescription() != null) row.setDescription(request.getDescription());
        if (request.getSynonyms() != null) row.setSynonyms(request.getSynonyms());
        if (request.getQuestion() != null) row.setQuestion(request.getQuestion());
        if (request.getAnswer() != null) row.setAnswer(request.getAnswer());
        row.setUpdateTime(new Date());
        businessKnowledgeDao.updateById(row);

        embedSoft(row);
        return toResponse(row);
    }

    public void delete(Long userId, Long knowledgeId) {
        BusinessKnowledge row = businessKnowledgeDao.selectById(knowledgeId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Knowledge not found or does not belong to this user");
        }
        businessKnowledgeDao.deleteById(knowledgeId);
        // best-effort vector removal — never fail the row delete on vector error
        try {
            knowledgeEmbeddingService.embedAndDelete(knowledgeId);
        } catch (Exception e) {
            log.warn("[BusinessKnowledgeAppService] Failed to delete vector for {}: {}", knowledgeId, e.getMessage());
        }
    }

    /**
     * Save-then-embed with soft failure: on embedding error keep the row and mark
     * {@code status=0} (pending re-embed) so it can be backfilled later.
     */
    private void embedSoft(BusinessKnowledge row) {
        try {
            knowledgeEmbeddingService.embedAndUpsert(row);
            if (row.getStatus() == null || row.getStatus() != 1) {
                row.setStatus(1);
                businessKnowledgeDao.updateById(row);
            }
        } catch (Exception e) {
            log.error("[BusinessKnowledgeAppService] Embedding failed for row {} (kept, status=0): {}",
                    row.getId(), e.getMessage(), e);
            row.setStatus(0);
            businessKnowledgeDao.updateById(row);
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