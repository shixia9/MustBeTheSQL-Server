package com.sql.logic.engine.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Response describing a sandbox session — returned by {@code /list} and
 * {@code /connect}.
 */
@Data
@AllArgsConstructor
public class SandboxSessionResponse {
    private String sessionId;
    private String language;
    private String status;
    private long createdAt;
    private long lastAccessed;
}
