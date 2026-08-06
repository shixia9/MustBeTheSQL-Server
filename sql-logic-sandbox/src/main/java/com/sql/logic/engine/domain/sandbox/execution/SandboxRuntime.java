package com.sql.logic.engine.domain.sandbox.execution;

import java.util.List;
import java.util.Map;

/**
 * Sandbox runtime manager.
 *
 * <p>A runtime owns a pool of {@link SandboxSession}s and is responsible for
 * their full lifecycle: creation, lookup, enumeration, idle cleanup, and health
 * reporting. Concrete implementations include {@code DockerSandboxRuntime}
 * (long-lived containers) and {@code LocalSandboxRuntime} (host processes, opt-in).
 *
 * <p>Sessions are keyed by a caller-provided {@code sessionId};
 * the runtime does not generate ids itself. The {@link RuntimeFactory} selects
 * the best available runtime at boot.
 */
public interface SandboxRuntime {

    /** Runtime identifier (e.g. "docker", "local"). */
    String runtimeId();

    /**
     * Create and start a new sandbox session.
     *
     * @param sessionId caller-provided unique session id
     * @param config    session configuration
     * @return the started session
     * @throws IllegalArgumentException if the session id already exists
     * @throws RuntimeException         if the session could not be started
     */
    SandboxSession createSession(String sessionId, SessionConfig config);

    /**
     * Destroy a session (stop container / clean working dir).
     *
     * @return true if a session was found and destroyed; false if not found
     */
    boolean destroySession(String sessionId);

    /** List all active session ids. */
    List<String> listSessions();

    /** Get a session by id (null if not found). */
    SandboxSession getSession(String sessionId);

    /**
     * Clean up sessions idle for longer than {@code maxIdleSeconds}.
     *
     * @param maxIdleSeconds idle threshold (default 3600 = 1 hour)
     * @return number of sessions cleaned up
     */
    int cleanupExpiredSessions(long maxIdleSeconds);

    /**
     * Runtime health check (e.g. Docker daemon reachable, system resources).
     *
     * @return a map with at least a "status" key ("healthy" / "unhealthy")
     */
    Map<String, Object> healthCheck();

    /** Whether this runtime can execute the given language. */
    boolean supportsLanguage(String language);
}
