package com.sql.logic.engine.domain.agentic.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqlCandidateScorerTest {

    private SqlCandidateScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new SqlCandidateScorer(null); // No LLM — rule-only mode
    }

    @Test
    void shouldFilterOutNullSql() {
        List<SqlCandidate> candidates = List.of(
                new SqlCandidate(null, Map.of()),
                new SqlCandidate("SELECT 1", Map.of())
        );
        List<SqlCandidate> result = scorer.filter(candidates);
        assertEquals(1, result.size());
        assertEquals("SELECT 1", result.get(0).getSql());
    }

    @Test
    void shouldFilterOutEmptySql() {
        List<SqlCandidate> candidates = List.of(
                new SqlCandidate("", Map.of()),
                new SqlCandidate("SELECT 1", Map.of())
        );
        List<SqlCandidate> result = scorer.filter(candidates);
        assertEquals(1, result.size());
    }

    @Test
    void shouldFilterOutNonSelectSql() {
        List<SqlCandidate> candidates = List.of(
                new SqlCandidate("INSERT INTO t VALUES(1)", Map.of()),
                new SqlCandidate("UPDATE t SET x=1", Map.of()),
                new SqlCandidate("DELETE FROM t", Map.of()),
                new SqlCandidate("SELECT * FROM t", Map.of())
        );
        List<SqlCandidate> result = scorer.filter(candidates);
        assertEquals(1, result.size());
        assertEquals("SELECT * FROM t", result.get(0).getSql());
    }

    @Test
    void shouldPassWithClause() {
        List<SqlCandidate> candidates = List.of(
                new SqlCandidate("WITH cte AS (SELECT 1) SELECT * FROM cte", Map.of())
        );
        List<SqlCandidate> result = scorer.filter(candidates);
        assertEquals(1, result.size());
        assertTrue(result.get(0).isValid());
    }

    @Test
    void shouldFilterOutInvalidSyntax() {
        List<SqlCandidate> candidates = List.of(
                new SqlCandidate("SELECT FROM WHERE", Map.of()),
                new SqlCandidate("SELECT * FROM t", Map.of())
        );
        List<SqlCandidate> result = scorer.filter(candidates);
        assertEquals(1, result.size());
        assertEquals("SELECT * FROM t", result.get(0).getSql());
    }

    @Test
    void shouldAssignRuleScores() {
        List<SqlCandidate> candidates = List.of(
                new SqlCandidate("SELECT * FROM t", Map.of())
        );
        List<SqlCandidate> result = scorer.filter(candidates);
        assertEquals(1, result.size());
        assertTrue(result.get(0).getRuleScore() > 0.3);
        assertTrue(result.get(0).getRuleScore() <= 1.0);
    }

    @Test
    void shouldSelectBestByCompositeScore() {
        List<SqlCandidate> candidates = new ArrayList<>();
        SqlCandidate a = new SqlCandidate("SELECT * FROM t WHERE id = 1", Map.of("idx", 0));
        a.setRuleScore(0.9);
        a.setLlmScore(0.8); // composite = 0.4*0.9 + 0.6*0.8 = 0.84

        SqlCandidate b = new SqlCandidate("SELECT * FROM t WHERE id = 2", Map.of("idx", 1));
        b.setRuleScore(0.5);
        b.setLlmScore(0.9); // composite = 0.4*0.5 + 0.6*0.9 = 0.74

        candidates.add(a);
        candidates.add(b);

        SqlCandidate best = scorer.selectBest(candidates);
        assertNotNull(best);
        assertEquals("SELECT * FROM t WHERE id = 1", best.getSql());
    }

    @Test
    void shouldHandleEmptyCandidateList() {
        assertNull(scorer.selectBest(List.of()));
    }

    @Test
    void shouldRankSingleCandidate() {
        List<SqlCandidate> candidates = List.of(
                new SqlCandidate("SELECT 1", Map.of())
        );
        scorer.rankByLLM(candidates, "test", "");
        assertEquals(0.8, candidates.get(0).getLlmScore(), 0.01);
    }

    @Test
    void shouldHandleRankFailureGracefully() {
        List<SqlCandidate> candidates = List.of(
                new SqlCandidate("SELECT a FROM t", Map.of()),
                new SqlCandidate("SELECT b FROM t", Map.of())
        );
        // No LLM available → should fallback to rule scores
        scorer.rankByLLM(candidates, "test query", "schema");
        // Both should have scores assigned (rule score fallback)
        assertTrue(candidates.get(0).getLlmScore() > 0);
        assertTrue(candidates.get(1).getLlmScore() > 0);
    }
}
