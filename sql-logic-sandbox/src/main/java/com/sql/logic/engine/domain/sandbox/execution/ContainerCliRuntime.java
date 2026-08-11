package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxConfig;
import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Abstract base for CLI-based container runtimes (Docker / Podman / Nerdctl).
 *
 * <p>Each runtime owns a pool of {@link ContainerCliSandboxSession}s keyed by
 * session id. Sessions are long-lived containers ({@code <cli> run -d ... tail -f
 * /dev/null}) that persist state across multiple {@code execute()} calls.
 */
public abstract class ContainerCliRuntime implements SandboxRuntime {

    private static final Logger log = LoggerFactory.getLogger(ContainerCliRuntime.class);

    protected final SandboxProperties properties;
    protected final ConcurrentHashMap<String, ContainerCliSandboxSession> sessions = new ConcurrentHashMap<>();

    protected ContainerCliRuntime(SandboxProperties properties) {
        this.properties = properties;
    }

    // ---- Subclass hooks ----

    /** The CLI binary path (e.g. "docker", "podman", "/usr/local/bin/nerdctl"). */
    protected abstract String cliBinary();

    /** The runtime id ("docker" / "podman" / "nerdctl"). */
    @Override
    public abstract String runtimeId();

    /**
     * Probe whether this CLI is available and the daemon responds. Subclasses
     * delegate to the appropriate version/info command.
     */
    public abstract boolean isCliAvailable();

    // ---- SandboxRuntime implementation (shared) ----

    @Override
    public ContainerCliSandboxSession createSession(String sessionId, SessionConfig config) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalArgumentException("Session already exists: " + sessionId);
        }
        ContainerCliSandboxSession session =
                new ContainerCliSandboxSession(sessionId, config, cliBinary(), properties);
        if (!session.start()) {
            throw new RuntimeException("Failed to start " + runtimeId() + " sandbox session: " + sessionId);
        }
        sessions.put(sessionId, session);
        log.info("[{}] Created session {} (language={})", runtimeId(), sessionId, config.language());
        return session;
    }

    @Override
    public boolean destroySession(String sessionId) {
        ContainerCliSandboxSession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        boolean stopped = session.stop();
        log.info("[{}] Destroyed session {} (stopped={})", runtimeId(), sessionId, stopped);
        return true;
    }

    @Override
    public List<String> listSessions() {
        return new ArrayList<>(sessions.keySet());
    }

    @Override
    public ContainerCliSandboxSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public int cleanupExpiredSessions(long maxIdleSeconds) {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, ContainerCliSandboxSession> entry : sessions.entrySet()) {
            long idleMs = now - entry.getValue().lastAccessed();
            if (idleMs > maxIdleSeconds * 1000) {
                expired.add(entry.getKey());
            }
        }
        int cleaned = 0;
        for (String sid : expired) {
            if (destroySession(sid)) {
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("[{}] Cleaned up {} expired sessions (idle > {}s)", runtimeId(), cleaned, maxIdleSeconds);
        }
        return cleaned;
    }

    @Override
    public Map<String, Object> healthCheck() {
        Map<String, Object> health = new LinkedHashMap<>();
        try {
            Process p = new ProcessBuilder(cliBinary(), "version").start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (done && p.exitValue() == 0) {
                String version = new String(p.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                health.put("status", "healthy");
                health.put("runtime", runtimeId());
                health.put("version", version);
                health.put("activeSessions", sessions.size());
                health.put("supportedLanguages", new ArrayList<>(SandboxConfig.LANGUAGE_IMAGES.keySet()));
            } else {
                health.put("status", "unhealthy");
                health.put("runtime", runtimeId());
                health.put("error", cliBinary() + " version failed (exit="
                        + (done ? p.exitValue() : "timeout") + ")");
            }
        } catch (Exception e) {
            health.put("status", "unhealthy");
            health.put("runtime", runtimeId());
            health.put("error", e.getMessage());
        }
        return health;
    }

    @Override
    public boolean supportsLanguage(String language) {
        if (language == null) {
            return false;
        }
        return SandboxConfig.LANGUAGE_IMAGES.containsKey(language.toLowerCase());
    }
}
