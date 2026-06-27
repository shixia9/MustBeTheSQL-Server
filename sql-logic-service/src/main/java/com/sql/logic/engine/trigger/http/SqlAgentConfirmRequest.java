package com.sql.logic.engine.trigger.http;

import lombok.Data;

/**
 * Request body for the Phase 4 HITL {@code /api/v1/agent/sql/continue} endpoint.
 * <p>
 * Resumes a graph paused before the HITL node by injecting the human decision.
 * The {@code threadId} must match a pending session registered by the prior
 * {@code /stream} call, and must belong to the authenticated user.
 */
@Data
public class SqlAgentConfirmRequest {
    /** The pending run's thread identifier (returned to the frontend as AWAITING_CONFIRMATION.threadId). */
    private String threadId;
    /** true = approve and proceed to PLAN_DISPATCH; false = reject and re-plan with the feedback. */
    private Boolean approved;
    /** Optional modification feedback (used when approved=false, also forwarded as a comment when true). */
    private String feedback;
    /** For user-session cross-check (must match the logged-in user; optional if session auth suffices). */
    private Long userId;
}