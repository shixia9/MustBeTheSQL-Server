package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sql.logic.engine.common.dto.BusinessKnowledgeCreateRequest;
import com.sql.logic.engine.common.dto.BusinessKnowledgeUpdateRequest;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.infrastructure.dao.BusinessKnowledgeDao;
import com.sql.logic.engine.infrastructure.po.BusinessKnowledge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessKnowledgeAppServiceTest {
    private final BusinessKnowledgeDao dao = mock(BusinessKnowledgeDao.class);
    private final KnowledgeEmbeddingService embedder = mock(KnowledgeEmbeddingService.class);
    private final DatabaseAppService databases = mock(DatabaseAppService.class);
    private final RedissonClient redis = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final BusinessKnowledgeAppService service = new BusinessKnowledgeAppService(dao, embedder, databases, redis);

    @BeforeEach
    void locks() throws Exception {
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(eq(5L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(dao.insert(any(BusinessKnowledge.class))).thenAnswer(inv -> {
            ((BusinessKnowledge) inv.getArgument(0)).setId(10L);
            return 1;
        });
        when(dao.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    @Test
    void embeddingFailureLeavesPendingRowForRetry() {
        doThrow(new RuntimeException("embedding offline")).when(embedder).embedAndUpsert(any());
        BusinessKnowledgeCreateRequest request = new BusinessKnowledgeCreateRequest();
        request.setConnectionId(9L);
        request.setVectorType(SqlAgentSpec.Retrieval.VectorType.QUESTION_KNOWLEDGE);
        request.setQuestion("sales");
        var response = service.create(7L, request);
        assertEquals(0, response.getStatus());
        assertEquals(10L, response.getId());
        verify(dao, never()).delete(any(Wrapper.class));
    }

    @Test
    void deleteFailureLeavesHiddenTombstoneAndRetryRemovesIt() {
        BusinessKnowledge row = row();
        when(dao.selectById(10L)).thenReturn(row);
        doThrow(new RuntimeException("pg offline")).doNothing().when(embedder).embedAndDelete(10L);
        service.delete(7L, 10L);
        verify(dao, never()).delete(any(Wrapper.class));
        row.setStatus(2);
        service.retryPending(10L);
        verify(dao).delete(any(Wrapper.class));
    }

    @Test
    void updateRejectsEmptyQuestionBeforeWriting() {
        when(dao.selectById(10L)).thenReturn(row());
        BusinessKnowledgeUpdateRequest request = new BusinessKnowledgeUpdateRequest();
        request.setId(10L);
        request.setQuestion(" ");
        assertThrows(IllegalArgumentException.class, () -> service.update(7L, request));
        verify(dao, never()).update(any(), any(Wrapper.class));
        verifyNoInteractions(embedder);
    }

    private BusinessKnowledge row() {
        BusinessKnowledge row = new BusinessKnowledge();
        row.setId(10L);
        row.setUserId(7L);
        row.setConnectionId(9L);
        row.setKnowledgeVersion(1L);
        row.setStatus(1);
        row.setVectorType(SqlAgentSpec.Retrieval.VectorType.QUESTION_KNOWLEDGE);
        row.setQuestion("sales");
        return row;
    }
}
