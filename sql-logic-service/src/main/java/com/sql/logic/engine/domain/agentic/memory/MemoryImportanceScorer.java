package com.sql.logic.engine.domain.agentic.memory;

import com.sql.logic.engine.domain.agentic.core.MemoryFragment;

/**
 * Stub importance scorer for Phase 1.
 * <p>
 * In Phase 3, this will be replaced with an LLM-based scorer that
 * evaluates memory importance on a 0.0–1.0 scale using a dedicated
 * prompt template (mirroring DB-GPT's {@code ImportanceScorer}).
 * Phase 1 returns a fixed default to satisfy the interface contract.
 */
public class MemoryImportanceScorer {

    private static final double DEFAULT_IMPORTANCE = 0.5;

    /**
     * Score the importance of a memory fragment.
     * Phase 1: returns a fixed default of 0.5.
     * Phase 3: delegates to LLM for semantic importance evaluation.
     */
    public double score(MemoryFragment fragment) {
        return DEFAULT_IMPORTANCE;
    }

    /**
     * Score raw observation text before a fragment is created.
     */
    public double score(String observation, String memoryType) {
        return DEFAULT_IMPORTANCE;
    }
}
