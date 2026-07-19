package com.sql.logic.engine.domain.agentic.core;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A single unit of Agent memory — an observation, its importance,
 * its type (PROFILE, TASK, FACT, EPISODIC), optional embeddings,
 * and whether it was derived as an insight.
 */
public record MemoryFragment(
        String observation,
        double importance,
        String memoryType,
        boolean isInsight,
        List<Float> embeddings,
        LocalDateTime timestamp,
        Long memoryId
) {
    public MemoryFragment {
        if (observation == null || observation.isBlank()) {
            throw new IllegalArgumentException("Memory observation must not be blank");
        }
        if (importance < 0.0 || importance > 1.0) {
            throw new IllegalArgumentException("Importance must be in [0.0, 1.0]");
        }
        timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    // --- Backward-compatible constructors ---

    public MemoryFragment(String observation, double importance, String memoryType, LocalDateTime timestamp) {
        this(observation, importance, memoryType, false, null, timestamp, null);
    }

    public static MemoryFragment of(String observation, String memoryType, double importance) {
        return new MemoryFragment(observation, importance, memoryType, false, null, LocalDateTime.now(), null);
    }

    public static MemoryFragment of(String observation, String memoryType) {
        return new MemoryFragment(observation, 0.5, memoryType, false, null, LocalDateTime.now(), null);
    }

    // --- Builder-style with* methods for immutable updates ---

    public MemoryFragment withImportance(double newImportance) {
        return new MemoryFragment(observation, newImportance, memoryType, isInsight, embeddings, timestamp, memoryId);
    }

    public MemoryFragment withInsight(boolean insight) {
        return new MemoryFragment(observation, importance, memoryType, insight, embeddings, timestamp, memoryId);
    }

    public MemoryFragment withEmbeddings(List<Float> newEmbeddings) {
        return new MemoryFragment(observation, importance, memoryType, isInsight, newEmbeddings, timestamp, memoryId);
    }

    public MemoryFragment withMemoryId(Long id) {
        return new MemoryFragment(observation, importance, memoryType, isInsight, embeddings, timestamp, id);
    }

    /** Copy this fragment with the given observation text (used for insight reduction). */
    public MemoryFragment withObservation(String newObservation) {
        return new MemoryFragment(newObservation, importance, memoryType, isInsight, embeddings, timestamp, memoryId);
    }

    /** Reduce multiple fragments into a single observation (joined by semicolons). */
    public static MemoryFragment reduce(List<MemoryFragment> fragments, String memoryType) {
        if (fragments == null || fragments.isEmpty()) {
            throw new IllegalArgumentException("Cannot reduce empty fragment list");
        }
        StringBuilder sb = new StringBuilder();
        double avgImportance = 0;
        for (MemoryFragment f : fragments) {
            sb.append(f.observation).append("; ");
            avgImportance += f.importance;
        }
        avgImportance /= fragments.size();
        return new MemoryFragment(sb.toString(), avgImportance, memoryType, true, null, LocalDateTime.now(), null);
    }
}
