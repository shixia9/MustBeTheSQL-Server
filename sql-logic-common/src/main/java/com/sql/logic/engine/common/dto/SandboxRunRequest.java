package com.sql.logic.engine.common.dto;

import lombok.Data;

/**
 * One-shot manual execution request — connects an ephemeral sandbox session,
 * executes the code, and disconnects in a single synchronous call. Backs the
 * frontend "Run" button on Python code blocks.
 */
@Data
public class SandboxRunRequest {

    /** Language key: python / bash / javascript / ... Defaults to "python". */
    private String language;

    /** The source code to execute. */
    private String code;

    /** Per-execution timeout in seconds (0/null = server default). */
    private Integer timeout;
}
