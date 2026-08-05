package com.sql.logic.engine.common.dto;

import lombok.Data;

/**
 * Request to execute code in a sandbox session.
 */
@Data
public class SandboxExecuteRequest {
    /** The session id returned by {@code /connect}. */
    private String sessionId;

    /** The source code to execute. */
    private String code;

    /** Override the session language for this execution (optional). */
    private String language;
}
