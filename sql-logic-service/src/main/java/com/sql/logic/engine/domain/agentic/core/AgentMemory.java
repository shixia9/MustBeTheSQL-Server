package com.sql.logic.engine.domain.agentic.core;

import java.util.List;

/**
 * Agent memory abstraction — three-tier architecture
 * SensoryMemory → ShortTermMemory → LongTermMemory cascade.
 * <p>
 * LLM-based importance scoring, insight extraction,
 * embedding-based deduplication, and database-backed task progress snapshots.
 */
public interface AgentMemory {

    /** Recall memories relevant to the given query. */
    String read(String query);

    /** Persist a memory fragment through the three-tier cascade. */
    void write(MemoryFragment fragment);

    /** Write multiple fragments (e.g., for memory recovery). */
    default void writeBatch(List<MemoryFragment> fragments) {
        for (MemoryFragment f : fragments) write(f);
    }

    /** Record a completed task step for the never-lost progress tracker. */
    void recordTaskProgress(TaskProgressEntry entry);

    /** Get a summary of completed task steps for system prompt injection. */
    String getTaskProgressSummary();

    /** Get recent memory fragments (most recent first). */
    List<MemoryFragment> getRecentFragments(int count);

    /** Clear all memory fragments. */
    List<MemoryFragment> clear();

    /** Get the total count of fragments across all tiers. */
    int totalFragmentCount();

    /** Transfer short-term overflow to long-term storage (explicit flush). */
    void flushToLongTerm();

    /** A single task progress entry in the never-lost tracker. */
    record TaskProgressEntry(
            int step,
            String action,
            String phase,
            TaskStatus status,
            String snapshot
    ) {}

    enum TaskStatus { DONE, FAILED }
}
