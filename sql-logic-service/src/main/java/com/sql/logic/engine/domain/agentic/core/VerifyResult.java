package com.sql.logic.engine.domain.agentic.core;

/**
 * Result of the {@code verify()} stage in the generate_reply pipeline.
 * Determines whether the action output passes correctness validation
 * and whether the retry loop should continue.
 */
public record VerifyResult(
        boolean passed,
        String reason
) {
    public static final VerifyResult PASSED = new VerifyResult(true, "Verification passed");

    public static VerifyResult fail(String reason) {
        return new VerifyResult(false, reason != null ? reason : "Verification failed");
    }
}
