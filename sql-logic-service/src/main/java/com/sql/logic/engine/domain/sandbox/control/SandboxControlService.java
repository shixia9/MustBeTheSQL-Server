package com.sql.logic.engine.domain.sandbox.control;

import com.sql.logic.engine.common.dto.SandboxMethodsResponse;
import com.sql.logic.engine.common.dto.SandboxSessionResponse;
import com.sql.logic.engine.domain.sandbox.config.SandboxConfig;
import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import com.sql.logic.engine.domain.sandbox.display.DisplayLayer;
import com.sql.logic.engine.domain.sandbox.display.DisplayResult;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionResult;
import com.sql.logic.engine.domain.sandbox.execution.RuntimeFactory;
import com.sql.logic.engine.domain.sandbox.execution.SandboxRuntime;
import com.sql.logic.engine.domain.sandbox.execution.SandboxSession;
import com.sql.logic.engine.domain.sandbox.execution.SessionConfig;
import com.sql.logic.engine.domain.sandbox.execution.StreamCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Sandbox control layer.
 *
 * <p>Orchicates the sandbox runtime by task type (connect / configure / execute /
 * disconnect / status / list / get_file). A {@link ReentrantLock} per session id
 * ensures that operations on the same session are serialised.
 *
 * <p>Also runs a scheduled idle-session cleanup every 10 minutes, destroying
 * sessions that have been idle longer than {@code sandbox.session-idle-timeout}.
 */
@Service
public class SandboxControlService {

    private static final Logger log = LoggerFactory.getLogger(SandboxControlService.class);

    private final RuntimeFactory runtimeFactory;
    private final SandboxProperties properties;
    private final DisplayLayer displayLayer;
    private final ConcurrentHashMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    public SandboxControlService(RuntimeFactory runtimeFactory, SandboxProperties properties,
                                 DisplayLayer displayLayer) {
        this.runtimeFactory = runtimeFactory;
        this.properties = properties;
        this.displayLayer = displayLayer;
    }

    // ========================================================================
    //  Connect — create a new session
    // ========================================================================

    /**
     * Create and start a new sandbox session.
     *
     * @param language the execution language (default: properties.defaultLanguage)
     * @param timeout  per-execution timeout in seconds (0/null = server default)
     * @return the new session id
     */
    public String connect(String language, Integer timeout) {
        SandboxRuntime runtime = runtimeFactory.getRuntime();
        String lang = (language == null || language.isBlank())
                ? properties.getDefaultLanguage() : language.toLowerCase();
        int timeoutSec = (timeout != null && timeout > 0) ? timeout : properties.getTimeout();

        String sessionId = "sbx-" + UUID.randomUUID().toString().substring(0, 12);
        SessionConfig config = SessionConfig.builder(lang)
                .timeoutSeconds(timeoutSec)
                .maxMemoryBytes(parseMemoryBytes(properties.getMemoryLimit()))
                .maxCpus((int) Math.ceil(properties.getCpus()))
                .networkDisabled(true)
                .build();

        runtime.createSession(sessionId, config);
        log.info("[SandboxControl] Connected session {} (language={}, timeout={}s)", sessionId, lang, timeoutSec);
        return sessionId;
    }

    // ========================================================================
    //  Execute — run code in a session
    // ========================================================================

    /**
     * Execute code in a session with optional streaming callback.
     *
     * @param sessionId the target session
     * @param code      the source code
     * @param callback  line-by-line output callback (may be null for batch mode)
     * @return the execution result
     */
    public ExecutionResult execute(String sessionId, String code, StreamCallback callback) {
        return withSessionLock(sessionId, () -> {
            SandboxSession session = requireSession(sessionId);
            ExecutionResult result = session.execute(code, callback);
            recordDisplay(sessionId, result);
            return result;
        });
    }

    /**
     * Execute code in a session with optional stdin bytes and streaming callback.
     *
     * <p>The {@code stdin} bytes are piped natively into the sandbox process
     * (Docker: {@code docker exec -i}; Local: {@code ProcessBuilder} redirect),
     * replacing the legacy base64 {@code sys.stdin} shim. When {@code stdin} is
     * null/empty, behaviour is identical to {@link #execute(String, String, StreamCallback)}.
     *
     * @param sessionId the target session
     * @param code      the source code
     * @param stdin     raw stdin bytes (e.g. the inputJson); null for no stdin
     * @param callback  line-by-line output callback (may be null for batch mode)
     * @return the execution result
     */
    public ExecutionResult execute(String sessionId, String code, byte[] stdin, StreamCallback callback) {
        return withSessionLock(sessionId, () -> {
            SandboxSession session = requireSession(sessionId);
            ExecutionResult result = session.execute(code, stdin, callback);
            recordDisplay(sessionId, result);
            return result;
        });
    }

    // ========================================================================
    //  Configure — install dependencies
    // ========================================================================

    public ExecutionResult configure(String sessionId, List<String> dependencies) {
        return configure(sessionId, dependencies, null);
    }

    /**
     * Install dependencies and optionally apply configuration overrides.
     *
     * @param sessionId   the target session
     * @param dependencies list of package specifiers
     * @param configInfo  optional config overrides (env, timeout); null = no overrides
     * @return the installation result
     */
    public ExecutionResult configure(String sessionId, List<String> dependencies,
                                     java.util.Map<String, Object> configInfo) {
        return withSessionLock(sessionId, () -> {
            SandboxSession session = requireSession(sessionId);
            // Apply config overrides before installing dependencies (e.g. env vars
            // that pip needs).
            if (configInfo != null && !configInfo.isEmpty()) {
                applyConfigInfo(session, configInfo);
            }
            ExecutionResult result = session.installDependencies(dependencies);
            recordDisplay(sessionId, result);
            return result;
        });
    }

    /**
     * One-shot manual execution — connect, execute, disconnect in a single call.
     * Used by the {@code /run} endpoint for ephemeral code execution without a
     * prior {@code /connect}.
     *
     * @param language  the execution language (default: python)
     * @param code      the source code
     * @param timeout   per-execution timeout (0/null = server default)
     * @return the execution result
     */
    public ExecutionResult manual(String language, String code, Integer timeout) {
        String sessionId = null;
        try {
            sessionId = connect(language, timeout);
            return execute(sessionId, code, (StreamCallback) null);
        } finally {
            if (sessionId != null) {
                try {
                    disconnect(sessionId);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    // ========================================================================
    //  Disconnect — destroy a session
    // ========================================================================

    public boolean disconnect(String sessionId) {
        return withSessionLock(sessionId, () -> {
            SandboxRuntime runtime = runtimeFactory.getRuntime();
            boolean destroyed = runtime.destroySession(sessionId);
            sessionLocks.remove(sessionId);
            // Clear display-layer history for this session.
            displayLayer.clear(sessionId);
            if (destroyed) {
                log.info("[SandboxControl] Disconnected session {}", sessionId);
            }
            return destroyed;
        });
    }

    // ========================================================================
    //  Status / List / GetFile
    // ========================================================================

    public Map<String, Object> status() {
        SandboxRuntime runtime = runtimeFactory.getRuntime();
        Map<String, Object> health = runtime.healthCheck();
        health.put("selectedRuntime", runtimeFactory.selectedRuntimeId());
        health.put("selectionReason", runtimeFactory.selectionReason());

        // Collect per-session resource stats (memory/cpu) from active sessions.
        List<String> ids = runtime.listSessions();
        List<Map<String, Object>> sessionStats = new ArrayList<>();
        for (String id : ids) {
            SandboxSession session = runtime.getSession(id);
            if (session != null && session.isActive()) {
                Map<String, Object> s = new java.util.LinkedHashMap<>();
                s.put("sessionId", id);
                s.put("language", session.config().language());
                s.putAll(session.collectStats());
                sessionStats.add(s);
            }
        }
        health.put("sessions", sessionStats);
        health.put("activeSessionCount", sessionStats.size());
        return health;
    }

    public List<SandboxSessionResponse> list() {
        SandboxRuntime runtime = runtimeFactory.getRuntime();
        List<String> ids = runtime.listSessions();
        List<SandboxSessionResponse> responses = new ArrayList<>();
        for (String id : ids) {
            SandboxSession session = runtime.getSession(id);
            if (session != null) {
                responses.add(new SandboxSessionResponse(
                        session.sessionId(),
                        session.config().language(),
                        session.isActive() ? "running" : "stopped",
                        session.createdAt(),
                        session.lastAccessed()));
            }
        }
        return responses;
    }

    public DisplayResult getFile(String sessionId, String filename) {
        return withSessionLock(sessionId, () -> {
            SandboxSession session = requireSession(sessionId);
            DisplayResult result = session.getFileContent(filename);
            if (result != null) {
                displayLayer.addResult(sessionId, result);
            }
            return result;
        });
    }

    /** Get the most recent display result for a session (null if none). */
    public DisplayResult getLastResult(String sessionId) {
        return displayLayer.getLastResult(sessionId);
    }

    /**
     * Build the capabilities response for {@code GET /methods} — surfaces supported
     * languages, the selected runtime, VNC availability, and capability flags.
     */
    public SandboxMethodsResponse methods() {
        List<String> languages = new ArrayList<>(SandboxConfig.LANGUAGE_IMAGES.keySet());
        boolean vncSupported = properties.isVncEnabled()
                && languages.stream().anyMatch(l -> l.endsWith("-vnc"));
        java.util.Map<String, Object> caps = new java.util.LinkedHashMap<>();
        caps.put("dependencyInstall", true);
        caps.put("streamingOutput", true);
        caps.put("fileTransfer", true);
        caps.put("stdinPipe", true);
        caps.put("multiRuntime", true);
        return new SandboxMethodsResponse(
                languages,
                runtimeFactory.selectedRuntimeId(),
                runtimeFactory.selectionReason(),
                vncSupported,
                true,
                caps);
    }

    // ========================================================================
    //  Scheduled idle-session cleanup (every 10 minutes)
    // ========================================================================

    @Scheduled(fixedDelay = 600_000)
    public void cleanupIdleSessions() {
        if (!runtimeFactory.isAvailable()) {
            return;
        }
        try {
            int cleaned = runtimeFactory.getRuntime().cleanupExpiredSessions(properties.getSessionIdleTimeout());
            if (cleaned > 0) {
                log.info("[SandboxControl] Cleaned up {} idle sessions", cleaned);
            }
        } catch (Exception e) {
            log.warn("[SandboxControl] Idle cleanup failed: {}", e.getMessage());
        }
    }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private SandboxSession requireSession(String sessionId) {
        SandboxRuntime runtime = runtimeFactory.getRuntime();
        SandboxSession session = runtime.getSession(sessionId);
        if (session == null || !session.isActive()) {
            throw new IllegalArgumentException("Sandbox session not found or inactive: " + sessionId);
        }
        return session;
    }

    /** Record the display-layer projection of an execution result (best-effort). */
    private void recordDisplay(String sessionId, ExecutionResult result) {
        if (result != null && result.displayResult() != null) {
            displayLayer.addResult(sessionId, result.displayResult());
        }
    }

    /**
     * Parse the configInfo map and apply recognised overrides to the session.
     * Recognised keys: {@code env} (Map<String,String>), {@code timeout} (Integer).
     */
    @SuppressWarnings("unchecked")
    private void applyConfigInfo(SandboxSession session, java.util.Map<String, Object> configInfo) {
        java.util.Map<String, String> envOverrides = null;
        Integer timeoutOverride = null;
        Object envObj = configInfo.get("env");
        if (envObj instanceof java.util.Map) {
            envOverrides = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((java.util.Map<String, Object>) envObj).entrySet()) {
                envOverrides.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        Object timeoutObj = configInfo.get("timeout");
        if (timeoutObj instanceof Number n) {
            timeoutOverride = n.intValue();
        }
        session.applyConfig(envOverrides, timeoutOverride);
    }

    private <T> T withSessionLock(String sessionId, Supplier<T> action) {
        ReentrantLock lock = sessionLocks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** Parse a Docker-style memory string (e.g. "256m", "1g") into bytes. */
    private static long parseMemoryBytes(String memoryLimit) {
        if (memoryLimit == null || memoryLimit.isBlank()) {
            return com.sql.logic.engine.domain.sandbox.config.SandboxConfig.MAX_MEMORY;
        }
        String s = memoryLimit.trim().toLowerCase();
        try {
            if (s.endsWith("g")) {
                return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1024 * 1024 * 1024);
            } else if (s.endsWith("m")) {
                return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1024 * 1024);
            } else if (s.endsWith("k")) {
                return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1024);
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return com.sql.logic.engine.domain.sandbox.config.SandboxConfig.MAX_MEMORY;
        }
    }
}
