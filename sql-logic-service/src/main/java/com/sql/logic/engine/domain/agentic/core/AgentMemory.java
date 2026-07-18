package com.sql.logic.engine.domain.agentic.core;

import java.util.List;

/**
 * Agent memory abstraction — separates short-term recall from
 * long-term persistent storage.
 * <p>
 * Modeled after DB-GPT's three-tier memory (Sensory/ShortTerm/LongTerm).
 * Phase 1 provides a simple in-memory implementation ({@code SimpleAgentMemory}).
 * Later phases add pgvector-backed hybrid memory with importance scoring.
 */
public interface AgentMemory {

    /**
     * Recall memories relevant to the given query.
     *
     * @param query the search query (current user input or observation)
     * @return concatenated relevant memory content, or empty string
     */
    String read(String query);

    /**
     * Persist a memory fragment.
     */
    void write(MemoryFragment fragment);

    /**
     * Record a completed task step for the never-lost progress tracker.
     */
    void recordTaskProgress(TaskProgressEntry entry);

    /**
     * Get a summary of completed task steps, for injection into the system prompt.
     * Returns null if no steps have been recorded.
     */
    String getTaskProgressSummary();

    /**
     * Get recent memory fragments (most recent first).
     */
    List<MemoryFragment> getRecentFragments(int count);

    /**
     * A single task progress entry in the never-lost tracker.
     */
    record TaskProgressEntry(
            int step,
            String action,
            String phase,
            TaskStatus status,
            String snapshot
    ) {}

    enum TaskStatus {
        DONE, FAILED
    }
}
