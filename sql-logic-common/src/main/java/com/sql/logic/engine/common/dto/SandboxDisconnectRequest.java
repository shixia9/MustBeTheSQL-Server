package com.sql.logic.engine.common.dto;

import lombok.Data;

/**
 * Request to destroy (disconnect) a sandbox session.
 */
@Data
public class SandboxDisconnectRequest {
    /** The session id to destroy. */
    private String sessionId;
}
