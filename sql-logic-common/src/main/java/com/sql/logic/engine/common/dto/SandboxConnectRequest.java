package com.sql.logic.engine.common.dto;

import lombok.Data;

/**
 * Request to create (connect) a new sandbox session.
 */
@Data
public class SandboxConnectRequest {
    /** Language key: python/javascript/java/cpp/go/rust/bash. Defaults to "python". */
    private String language;

    /** Per-execution timeout in seconds (0 = use server default). */
    private Integer timeout;

    /** Max memory in bytes (0 = use server default). */
    private Long maxMemory;

    /** Max CPU count (0 = use server default). */
    private Integer maxCpus;
}
