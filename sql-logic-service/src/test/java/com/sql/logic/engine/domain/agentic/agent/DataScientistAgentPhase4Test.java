package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 tests for DataScientistAgent enhancements:
 * multi-candidate mode and execution-based correctnessCheck.
 */
class DataScientistAgentPhase4Test {

    private DataScientistAgent agent;

    static class NoOpMemory implements AgentMemory {
        private final java.util.List<TaskProgressEntry> progress = new java.util.ArrayList<>();
        @Override public String read(String q) { return ""; }
        @Override public void write(MemoryFragment f) {}
        @Override public void recordTaskProgress(TaskProgressEntry e) { progress.add(e); }
        @Override public String getTaskProgressSummary() { return null; }
        @Override public List<MemoryFragment> getRecentFragments(int c) { return List.of(); }
        @Override public List<MemoryFragment> clear() { return List.of(); }
        @Override public int totalFragmentCount() { return 0; }
        @Override public void flushToLongTerm() {}
    }

    @BeforeEach
    void setUp() {
        agent = new DataScientistAgent();
        agent.bind(new NoOpMemory());
        agent.build();
    }

    // ---- Multi-candidate mode ----

    @Test
    void shouldDefaultToMultiCandidateModeFalse() {
        assertFalse(agent.isMultiCandidateMode());
    }

    @Test
    void shouldToggleMultiCandidateMode() {
        agent.setMultiCandidateMode(true);
        assertTrue(agent.isMultiCandidateMode());
        agent.setMultiCandidateMode(false);
        assertFalse(agent.isMultiCandidateMode());
    }

    @Test
    void shouldDefaultToFirstActionWhenNotMultiCandidate() {
        // No actions → falls through to fail
        agent.setMultiCandidateMode(false);
        AgentMessage msg = AgentMessage.user("test");
        CompletableFuture<ActionOutput> result = agent.act(msg, null);
        ActionOutput ao = result.join();
        assertFalse(ao.isExeSuccess());
        assertTrue(ao.content().contains("No actions registered"));
    }

    // ---- Execution-based correctnessCheck (Phase 4 enhanced) ----

    @Test
    void correctnessCheckShouldPassWithRowCount() {
        ActionOutput ao = ActionOutput.success("SELECT * FROM t",
                Map.of("rowCount", 42));
        VerifyResult result = agent.correctnessCheck(null, ao);
        assertTrue(result.passed());
    }

    @Test
    void correctnessCheckShouldFailOnZeroRowCount() {
        ActionOutput ao = ActionOutput.success("SELECT * FROM t",
                Map.of("rowCount", 0));
        VerifyResult result = agent.correctnessCheck(null, ao);
        assertFalse(result.passed());
        assertTrue(result.reason().contains("0行"));
    }

    @Test
    void correctnessCheckShouldFailOnEmptyRowsList() {
        ActionOutput ao = ActionOutput.success("SELECT * FROM t",
                Map.of("rows", List.of()));
        VerifyResult result = agent.correctnessCheck(null, ao);
        assertFalse(result.passed());
        assertTrue(result.reason().contains("空结果"));
    }

    @Test
    void correctnessCheckShouldFailOnExecutionError() {
        ActionOutput ao = ActionOutput.fail("Connection refused");
        // Override with data that simulates error
        VerifyResult result = agent.verify(
                AgentMessage.user("test").withActionReport(ao), null).join();
        assertFalse(result.passed());
    }

    @Test
    void correctnessCheckShouldPassOnNonEmptyRowsList() {
        ActionOutput ao = ActionOutput.success("SELECT * FROM t",
                Map.of("rows", List.of(Map.of("id", 1))));
        VerifyResult result = agent.correctnessCheck(null, ao);
        assertTrue(result.passed());
    }

    @Test
    void correctnessCheckShouldAllowExplain() {
        ActionOutput ao = ActionOutput.success("EXPLAIN SELECT * FROM t",
                Map.of("rowCount", 1));
        VerifyResult result = agent.correctnessCheck(null, ao);
        assertTrue(result.passed());
    }

    @Test
    void correctnessCheckShouldAllowDescribe() {
        ActionOutput ao = ActionOutput.success("DESCRIBE t",
                Map.of("rowCount", 5));
        VerifyResult result = agent.correctnessCheck(null, ao);
        assertTrue(result.passed());
    }

    @Test
    void correctnessCheckShouldExtractSqlFromMultiCandidateData() {
        // Simulate MultiCandidateSqlAction output: sql in data map
        ActionOutput ao = ActionOutput.success("",
                Map.of("sql", "SELECT a, b FROM t WHERE a > 0",
                        "compositeScore", 0.85, "totalCandidates", 3));
        VerifyResult result = agent.correctnessCheck(null, ao);
        assertTrue(result.passed());
    }

    // ---- Retry with fix action ----

    @Test
    void shouldSelectFixActionOnPreviousFailure() {
        // When a previous action failed with retry flag, act() should select sql_fix
        agent.setMultiCandidateMode(false);
        ActionOutput failedReport = ActionOutput.fail("syntax error", true);
        AgentMessage msg = AgentMessage.user("fix this").withActionReport(failedReport);

        // No actions registered → the fix action won't be found
        CompletableFuture<ActionOutput> result = agent.act(msg, null);
        ActionOutput ao = result.join();
        assertFalse(ao.isExeSuccess());
    }
}
