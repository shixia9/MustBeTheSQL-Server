package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.util.EnvironmentDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-process sandbox runtime.
 *
 * <p>Manages a pool of {@link LocalSandboxSession}s. This runtime is <b>opt-in only</b>
 * — it is selected by {@code RuntimeFactory} only when
 * {@code sandbox.allow-local-runtime=true} and Docker is unavailable. Host execution
 * has weaker isolation than containers; {@code SecurityUtils} pattern blacklisting is
 * the primary defense.
 *
 * <p>Supported languages are detected at construction time by probing for each
 * interpreter on PATH (python, node, java, g++, go, rustc, bash).
 */
@Component
public class LocalSandboxRuntime implements SandboxRuntime {

    private static final Logger log = LoggerFactory.getLogger(LocalSandboxRuntime.class);

    private final ConcurrentHashMap<String, LocalSandboxSession> sessions = new ConcurrentHashMap<>();
    private final List<String> supportedLanguages;

    public LocalSandboxRuntime() {
        this.supportedLanguages = detectSupportedLanguages();
        log.info("[LocalRuntime] Detected supported languages: {}", supportedLanguages);
    }

    @Override
    public String runtimeId() {
        return "local";
    }

    @Override
    public LocalSandboxSession createSession(String sessionId, SessionConfig config) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalArgumentException("Session already exists: " + sessionId);
        }
        LocalSandboxSession session = new LocalSandboxSession(sessionId, config);
        if (!session.start()) {
            throw new RuntimeException("Failed to start local sandbox session: " + sessionId);
        }
        sessions.put(sessionId, session);
        log.info("[LocalRuntime] Created session {} (language={})", sessionId, config.language());
        return session;
    }

    @Override
    public boolean destroySession(String sessionId) {
        LocalSandboxSession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        session.stop();
        log.info("[LocalRuntime] Destroyed session {}", sessionId);
        return true;
    }

    @Override
    public List<String> listSessions() {
        return new ArrayList<>(sessions.keySet());
    }

    @Override
    public LocalSandboxSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    @Override
    public int cleanupExpiredSessions(long maxIdleSeconds) {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, LocalSandboxSession> entry : sessions.entrySet()) {
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
            log.info("[LocalRuntime] Cleaned up {} expired sessions", cleaned);
        }
        return cleaned;
    }

    @Override
    public Map<String, Object> healthCheck() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "healthy");
        health.put("systemInfo", EnvironmentDetector.getSystemInfo());
        health.put("activeSessions", sessions.size());
        health.put("supportedLanguages", supportedLanguages);
        return health;
    }

    @Override
    public boolean supportsLanguage(String language) {
        if (language == null) {
            return false;
        }
        return supportedLanguages.contains(language.toLowerCase());
    }

    private static List<String> detectSupportedLanguages() {
        List<String> languages = new ArrayList<>();
        String[][] probes = {
                {"python", "python"},
                {"javascript", "node"},
                {"java", "java"},
                {"cpp", "g++"},
                {"go", "go"},
                {"rust", "rustc"},
                {"bash", "bash"},
        };
        for (String[] entry : probes) {
            if (EnvironmentDetector.isCommandAvailable(entry[1])) {
                languages.add(entry[0]);
            }
        }
        // Ensure Python is always listed (we're running in a JVM, not guaranteed).
        if (!languages.contains("python") && EnvironmentDetector.isCommandAvailable("python3")) {
            languages.add("python");
        }
        return languages;
    }
}
