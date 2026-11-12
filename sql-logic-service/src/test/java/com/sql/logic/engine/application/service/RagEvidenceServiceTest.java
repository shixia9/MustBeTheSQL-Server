package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.infrastructure.dao.BusinessKnowledgeDao;
import com.sql.logic.engine.infrastructure.po.BusinessKnowledge;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagEvidenceServiceTest {
    private final VectorSearchService search = mock(VectorSearchService.class);
    private final BusinessKnowledgeDao dao = mock(BusinessKnowledgeDao.class);
    private final PromptManager prompts = mock(PromptManager.class);
    private final LlmClientManager llms = mock(LlmClientManager.class);
    private final RagEvidenceService service = new RagEvidenceService(search, dao, prompts, llms);

    @Test
    void rehydratesOnlyCurrentActiveTenantRows() {
        Document current = doc(1, 2);
        Document stale = doc(2, 1);
        Document otherUser = doc(3, 2);
        Document pending = doc(4, 2);
        when(search.recallQuestion("sales", 7L, 9L, 4))
                .thenReturn(List.of(current, stale, otherUser, pending));
        when(dao.selectById(1L)).thenReturn(row(1, 7, 9, 2, 1));
        when(dao.selectById(2L)).thenReturn(row(2, 7, 9, 2, 1));
        when(dao.selectById(3L)).thenReturn(row(3, 8, 9, 2, 1));
        when(dao.selectById(4L)).thenReturn(row(4, 7, 9, 2, 0));

        var evidence = service.recall("sales", 7L, 9L, 4, 0);
        assertEquals(1, evidence.faq().size());
        assertEquals(1L, evidence.faq().get(0).get("knowledgeId"));
        assertTrue(evidence.text().contains("SELECT 1"));
        assertFalse(evidence.text().contains("SELECT 2"));
    }

    @Test
    void rewriteUsesHistoryOnlyAndFallsBackOnFailure() {
        assertEquals("那华北呢", service.rewriteForHistory("那华北呢", "", 1L, 7L));
        verifyNoInteractions(prompts, llms);
        when(prompts.render(eq(SqlAgentSpec.PromptName.EVIDENCE_QUERY_REWRITE), anyMap())).thenReturn("prompt");
        LLMStrategy llm = mock(LLMStrategy.class);
        when(llms.resolveStrategy(1L, 7L)).thenReturn(llm);
        when(llm.generateSql(eq("prompt"), isNull())).thenReturn("{\"standalone_query\":\"查询华北销售额\"}");
        assertEquals("查询华北销售额", service.rewriteForHistory("那华北呢", "上轮: 销售额", 1L, 7L));
        when(llm.generateSql(eq("prompt"), isNull())).thenThrow(new RuntimeException("offline"));
        assertEquals("那华北呢", service.rewriteForHistory("那华北呢", "上轮: 销售额", 1L, 7L));
    }

    @Test
    void retrievalFailureReturnsEmptyEvidence() {
        when(search.recallGlossary(anyString(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new RuntimeException("pg unavailable"));
        assertEquals("无", service.recall("sales", 7L, 9L, 4, 0.6).text());
    }

    @Test
    void scoreThresholdExcludesLowConfidenceHit() {
        Document low = doc(1, 1);
        when(low.getScore()).thenReturn(0.4);
        when(search.recallQuestion("sales", 7L, 9L, 3)).thenReturn(List.of(low));
        assertEquals("无", service.recall("sales", 7L, 9L, 3, 0.6).text());
        verifyNoInteractions(dao);
    }

    private Document doc(long id, long version) {
        Document document = mock(Document.class);
        when(document.getMetadata()).thenReturn(Map.of("knowledgeId", String.valueOf(id),
                "knowledgeVersion", String.valueOf(version)));
        when(document.getScore()).thenReturn(0.9);
        return document;
    }

    private BusinessKnowledge row(long id, long userId, long connectionId, long version, int status) {
        BusinessKnowledge row = new BusinessKnowledge();
        row.setId(id);
        row.setUserId(userId);
        row.setConnectionId(connectionId);
        row.setVectorType(SqlAgentSpec.Retrieval.VectorType.QUESTION_KNOWLEDGE);
        row.setKnowledgeVersion(version);
        row.setStatus(status);
        row.setQuestion("Q" + id);
        row.setAnswer("SELECT " + id);
        return row;
    }
}
