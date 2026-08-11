package com.sql.logic.engine.domain.agentic.context;

/**
 * Context budget state levels.
 */
public enum TokenState {
    NORMAL,     // under warning threshold
    WARNING,    // >= 70% — trigger Layer 1 + Layer 2
    ERROR,      // >= 90% — trigger Layer 3
    CRITICAL,   // >= 95% — trigger Layer 3 immediately
    OVERFLOW;   // >= 100% — emergency

    public boolean isGte(TokenState other) {
        return this.ordinal() >= other.ordinal();
    }
}
