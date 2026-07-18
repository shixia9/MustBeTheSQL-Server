package com.sql.logic.engine.domain.agentic.core;

import java.time.LocalDateTime;

/**
 * A single unit of Agent memory — an observation, its importance,
 * its type (PROFILE, TASK, FACT, EPISODIC), and when it was recorded.
 */
public record MemoryFragment(
        String observation,
        double importance,
        String memoryType,
        LocalDateTime timestamp
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

    public static MemoryFragment of(String observation, String memoryType, double importance) {
        return new MemoryFragment(observation, importance, memoryType, LocalDateTime.now());
    }

    public static MemoryFragment of(String observation, String memoryType) {
        return new MemoryFragment(observation, 0.5, memoryType, LocalDateTime.now());
    }
}
