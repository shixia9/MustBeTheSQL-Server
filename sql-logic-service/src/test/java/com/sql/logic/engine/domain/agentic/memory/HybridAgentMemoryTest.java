package com.sql.logic.engine.domain.agentic.memory;

import com.sql.logic.engine.domain.agentic.core.AgentMemory;
import com.sql.logic.engine.domain.agentic.core.AgentMemory.TaskProgressEntry;
import com.sql.logic.engine.domain.agentic.core.AgentMemory.TaskStatus;
import com.sql.logic.engine.domain.agentic.core.MemoryFragment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the three-tier memory cascade: Sensory → ShortTerm → LongTerm.
 */
class HybridAgentMemoryTest {

    private HybridAgentMemory memory;

    @BeforeEach
    void setUp() {
        memory = new HybridAgentMemory(null); // No long-term service
    }

    @Test
    void shouldWriteAndReadFragments() {
        memory.write(MemoryFragment.of("User asked about sales data", "TASK", 0.7));

        String recall = memory.read("sales");
        assertTrue(recall.contains("sales data"), "Should recall recently written observation");
    }

    @Test
    void shouldCascadeFromSensoryToShortTerm() {
        // Sensory buffer is 3, so writing 4 fragments should push one to short-term
        for (int i = 1; i <= 4; i++) {
            memory.write(MemoryFragment.of("Observation " + i, "TASK", 0.8));
        }

        // Short-term should have fragments from the cascade
        assertTrue(memory.getShortTermMemory().size() >= 1,
                "Short-term should receive cascaded fragments");
    }

    @Test
    void shouldTrackTaskProgress() {
        memory.recordTaskProgress(new TaskProgressEntry(1, "Generated SQL", "SQL_GEN", TaskStatus.DONE, "SELECT * FROM t"));
        memory.recordTaskProgress(new TaskProgressEntry(2, "Executed SQL", "SQL_EXEC", TaskStatus.DONE, "42 rows"));

        String summary = memory.getTaskProgressSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("[DONE] Step 1"));
        assertTrue(summary.contains("[DONE] Step 2"));
    }

    @Test
    void shouldReturnNullProgressSummaryWhenEmpty() {
        assertNull(memory.getTaskProgressSummary());
    }

    @Test
    void shouldGetRecentFragments() {
        memory.write(MemoryFragment.of("Fragment A", "TASK", 0.5));
        memory.write(MemoryFragment.of("Fragment B", "TASK", 0.6));

        var recent = memory.getRecentFragments(1);
        assertEquals(1, recent.size());
    }

    @Test
    void shouldClearAllTiers() {
        memory.write(MemoryFragment.of("Test 1", "TASK", 0.5));
        memory.write(MemoryFragment.of("Test 2", "TASK", 0.5));
        memory.recordTaskProgress(new TaskProgressEntry(1, "Step", "Phase", TaskStatus.DONE, "snap"));

        var cleared = memory.clear();
        assertFalse(cleared.isEmpty(), "Clear should return cleared fragments");
        assertEquals(0, memory.totalFragmentCount());
        assertNull(memory.getTaskProgressSummary());
    }

    @Test
    void sensoryMemoryShouldOverflow() {
        SensoryMemory sensory = new SensoryMemory(2, 0.1);

        sensory.write(MemoryFragment.of("Low importance", "TASK", 0.05));
        sensory.write(MemoryFragment.of("High importance", "TASK", 0.8));
        var overflow = sensory.write(MemoryFragment.of("Another high", "TASK", 0.9));

        assertFalse(overflow.isEmpty(), "Buffer size 2 + 3rd write should overflow");
        // Low-importance fragment (0.05) should be kept, high-importance ones should overflow
        assertTrue(overflow.size() >= 1);
    }

    @Test
    void shortTermMemoryShouldDedup() {
        AgentShortTermMemory stm = new AgentShortTermMemory(5, 0.5);

        stm.write(MemoryFragment.of("I prefer using CTEs for complex queries", "TASK", 0.5));
        // Similar content — should be deduped
        var result = stm.write(MemoryFragment.of("I prefer using CTEs for complex queries", "TASK", 0.7));

        assertNull(result, "Duplicate should not cause overflow");
        assertEquals(1, stm.size(), "Duplicate should be merged, not added");
        assertEquals(0.7, stm.getShortTermMemories().get(0).importance(), 0.01,
                "Importance should be updated to max");
    }

    @Test
    void llmImportanceScorerShouldParseScores() {
        LLMImportanceScorer scorer = new LLMImportanceScorer(null); // No LLM

        double score = scorer.score(MemoryFragment.of("Test observation", "TASK", 0.5));
        assertTrue(score >= 0.0 && score <= 1.0, "Score should be normalized to [0, 1]");
    }

    @Test
    void llmInsightExtractorShouldHandleNoLLM() {
        LLMInsightExtractor extractor = new LLMInsightExtractor(null);
        var insights = extractor.extract(List.of(
                MemoryFragment.of("Test 1", "TASK", 0.5)
        ));
        assertTrue(insights.isEmpty(), "Without LLM, extraction should return empty");
    }

    @Test
    void taskProgressShouldSurviveFragmentClear() {
        memory.recordTaskProgress(new TaskProgressEntry(1, "Action", "Phase", TaskStatus.DONE, "snapshot"));

        // Clear fragments but not task progress (progress is independent)
        memory.getSensoryMemory().clear();
        memory.getShortTermMemory().clear();

        assertNotNull(memory.getTaskProgressSummary(), "Task progress should survive memory clear");
    }

    @Test
    void failedTaskProgressShouldBeTracked() {
        memory.recordTaskProgress(new TaskProgressEntry(1, "Failed action", "Phase", TaskStatus.FAILED, "error"));

        String summary = memory.getTaskProgressSummary();
        assertTrue(summary.contains("[FAILED]"));
    }

    @Test
    void flushToLongTermShouldTransferShortTerm() {
        // Write enough to overflow sensory buffer (size 3) into short-term
        for (int i = 1; i <= 5; i++) {
            memory.write(MemoryFragment.of("Data " + i, "TASK", 0.8));
        }

        int before = memory.totalFragmentCount();
        assertTrue(before > 0, "Should have fragments before flush");

        memory.flushToLongTerm();
        assertEquals(0, memory.totalFragmentCount(), "Flush should clear all tiers");
    }
}
