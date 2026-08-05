package com.sql.logic.engine.common.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Request to install dependencies in a sandbox session.
 *
 * <p>In addition to dependency installation, the {@code configInfo} field allows
 * the caller to override session configuration (environment variables, timeout)
 * at configure time.
 */
@Data
public class SandboxConfigureRequest {
    /** The session id returned by {@code /connect}. */
    private String sessionId;

    /** List of package specifiers (e.g. ["pandas", "scikit-learn"]). */
    private List<String> dependencies;

    /**
     * Optional session configuration overrides. Recognised keys:
     * <ul>
     *   <li>{@code env} — Map of environment variable overrides</li>
     *   <li>{@code timeout} — Per-execution timeout in seconds (Integer)</li>
     * </ul>
     * Unknown keys are ignored. When null/empty, no overrides are applied.
     */
    private Map<String, Object> configInfo;
}
