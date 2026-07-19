package com.sql.logic.engine.domain.agentic.context;

/**
 * Configuration for context budget management.
 * <p>
 * Controls thresholds at which each compaction layer fires,
 * how many recent rounds to always preserve, and hard limits on compaction attempts.
 */
public class ContextBudgetConfig {

    /** Default max context window (GPT-4 / GPT-4o). Override for other models. */
    public static final int DEFAULT_MAX_CONTEXT_TOKENS = 120_000;

    private final int maxContextTokens;
    private final double warningThreshold;
    private final double errorThreshold;
    private final double criticalThreshold;
    private final int reservedTokens;
    private final int minKeepRecentRounds;
    private final int maxCompactFailures;
    private final int maxObservationAgeRounds;
    private final int truncatedObservationMaxChars;
    private final int minKeepTokens;

    public ContextBudgetConfig() {
        this(DEFAULT_MAX_CONTEXT_TOKENS, 0.70, 0.90, 0.95, 4096, 3, 3, 5, 200, 10_000);
    }

    public ContextBudgetConfig(
            int maxContextTokens,
            double warningThreshold,
            double errorThreshold,
            double criticalThreshold,
            int reservedTokens,
            int minKeepRecentRounds,
            int maxCompactFailures,
            int maxObservationAgeRounds,
            int truncatedObservationMaxChars,
            int minKeepTokens) {
        this.maxContextTokens = maxContextTokens > 0 ? maxContextTokens : DEFAULT_MAX_CONTEXT_TOKENS;
        this.warningThreshold = warningThreshold;
        this.errorThreshold = errorThreshold;
        this.criticalThreshold = criticalThreshold;
        this.reservedTokens = reservedTokens;
        this.minKeepRecentRounds = minKeepRecentRounds;
        this.maxCompactFailures = maxCompactFailures;
        this.maxObservationAgeRounds = maxObservationAgeRounds;
        this.truncatedObservationMaxChars = truncatedObservationMaxChars;
        this.minKeepTokens = minKeepTokens;
    }

    /** Effective budget = max context minus reserved output space. */
    public int effectiveBudget() { return maxContextTokens - reservedTokens; }

    // --- Getters ---

    public int maxContextTokens() { return maxContextTokens; }
    public double warningThreshold() { return warningThreshold; }
    public double errorThreshold() { return errorThreshold; }
    public double criticalThreshold() { return criticalThreshold; }
    public int reservedTokens() { return reservedTokens; }
    public int minKeepRecentRounds() { return minKeepRecentRounds; }
    public int maxCompactFailures() { return maxCompactFailures; }
    public int maxObservationAgeRounds() { return maxObservationAgeRounds; }
    public int truncatedObservationMaxChars() { return truncatedObservationMaxChars; }
    public int minKeepTokens() { return minKeepTokens; }
}
