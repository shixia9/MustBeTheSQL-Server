package com.sql.logic.engine.domain.sandbox;

import com.sql.logic.engine.common.sink.SandboxEventSink;
import com.sql.logic.engine.domain.sandbox.audit.SandboxAuditService;
import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import com.sql.logic.engine.domain.sandbox.control.SandboxControlService;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionResult;
import com.sql.logic.engine.domain.sandbox.execution.RuntimeFactory;
import com.sql.logic.engine.domain.sandbox.execution.StreamCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain service for agent-facing sandbox execution — the single integration point
 * between the multi-agent system and the sandbox module.
 *
 * <p>Wraps {@link SandboxControlService} with agent-specific concerns:
 * <ul>
 *   <li><b>Per-thread session management</b> — each agent conversation (threadId)
 *       gets a dedicated stateful sandbox session. Dependencies installed in one
 *       execution are available in the next.</li>
 *   <li><b>Native stdin piping</b> — generated Python scripts read input from
 *       {@code sys.stdin}. The service converts {@code inputJson} to raw bytes and
 *       pipes them natively into the sandbox process (Docker: {@code docker exec -i};
 *       Local: {@code ProcessBuilder} redirect).</li>
 *   <li><b>AST validation</b> — all Python code is validated by
 *       {@link PythonAstValidator} before execution, regardless of runtime.</li>
 *   <li><b>Fail-closed</b> — when no sandbox runtime is available, execution is
 *       rejected with an error. There is no host-local fallback.</li>
 *   <li><b>SSE streaming</b> — Each execution emits {@code SANDBOX STARTED}
 *       → multiple {@code output_type: stream} chunks (stdout/stderr line-by-line,
 *       chunked at 800 chars to {@code chunk_text}) → {@code SANDBOX FINISHED}.
 *       Streaming is driven internally by this service via {@link SandboxEventSink};
 *       actions no longer need to supply a callback.</li>
 * </ul>
 */
@Service
public class SandboxExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SandboxExecutionService.class);

    /** Max characters per SSE stream chunk. */
    private static final int STREAM_CHUNK_MAX_LEN = 800;

    /** Max code length included in the STARTED event payload. */
    private static final int STARTED_CODE_PREVIEW_MAX = 2000;

    private final RuntimeFactory runtimeFactory;
    private final SandboxControlService controlService;
    private final SandboxProperties properties;
    private final SandboxEventSink eventSink;
    private final SandboxAuditService auditService;

    /** threadId → sandbox sessionId (stateful per-conversation sessions). */
    private final ConcurrentHashMap<String, String> threadSessions = new ConcurrentHashMap<>();

    public SandboxExecutionService(RuntimeFactory runtimeFactory,
                                   SandboxControlService controlService,
                                   SandboxProperties properties,
                                   SandboxEventSink eventSink,
                                   SandboxAuditService auditService) {
        this.runtimeFactory = runtimeFactory;
        this.controlService = controlService;
        this.properties = properties;
        this.eventSink = eventSink;
        this.auditService = auditService;
    }

    // ========================================================================
    //  Agent-facing execution
    // ========================================================================

    /**
     * Execute Python code for an agent conversation thread.
     *
     * <p>Fail-closed: when no sandbox runtime is available the call returns an
     * error result instead of falling back to host execution.
     *
     * @param threadId  the conversation thread id (for session reuse)
     * @param code      the Python source code
     * @param inputJson JSON data the script expects on stdin (may be null/"{}")
     * @param timeoutSec execution timeout (0/null = server default)
     * @param callback  streaming callback (may be null for batch mode)
     * @return the execution result
     */
    public ExecutionResult executePython(String threadId, String code, String inputJson,
                                         Long timeoutSec, StreamCallback callback) {
        if (code == null || code.isBlank()) {
            return ExecutionResult.error("No Python code to execute", -1, 0, "python", List.of());
        }

        // AST validation — enforced across all runtimes.
        List<String> astViolations = PythonAstValidator.validate(code);
        if (!astViolations.isEmpty()) {
            String msg = "Python code rejected by AST validator: " + String.join("; ", astViolations);
            log.warn("[SandboxExec] AST validation failed for thread {}: {}", threadId, msg);
            return ExecutionResult.error(msg, -1, 0, "python", astViolations);
        }

        // Fail-closed: no legacy fallback. Reject execution when no runtime is available.
        if (!runtimeFactory.isAvailable()) {
            String reason = runtimeFactory.selectionReason();
            log.warn("[SandboxExec] Rejecting Python execution for thread {} — no sandbox runtime. {}",
                    threadId, reason);
            return ExecutionResult.error(
                    "No sandbox runtime available: " + reason, -1, 0, "python", List.of());
        }

        return executeInSandbox(threadId, code, inputJson, timeoutSec, callback);
    }

    /**
     * Execute shell/bash code for an agent conversation thread.
     *
     * @param threadId  the conversation thread id
     * @param code      the shell script
     * @param timeoutSec execution timeout
     * @param callback  streaming callback
     * @return the execution result
     */
    public ExecutionResult executeShell(String threadId, String code, Long timeoutSec,
                                        StreamCallback callback) {
        if (code == null || code.isBlank()) {
            return ExecutionResult.error("No shell code to execute", -1, 0, "bash", List.of());
        }
        if (!runtimeFactory.isAvailable()) {
            return ExecutionResult.error(
                    "No sandbox runtime available for shell execution: " + runtimeFactory.selectionReason(),
                    -1, 0, "bash", List.of());
        }
        return executeInSandbox(threadId, code, null, timeoutSec, callback, "bash");
    }

    /** Destroy the sandbox session for a finished conversation thread. */
    public void destroyThreadSession(String threadId) {
        String sessionId = threadSessions.remove(threadId);
        if (sessionId != null) {
            try {
                controlService.disconnect(sessionId);
                log.info("[SandboxExec] Destroyed session {} for thread {}", sessionId, threadId);
            } catch (Exception e) {
                log.warn("[SandboxExec] Failed to destroy session {} for thread {}: {}",
                        sessionId, threadId, e.getMessage());
            }
        }
    }

    // ========================================================================
    //  Sandbox execution path
    // ========================================================================

    private ExecutionResult executeInSandbox(String threadId, String code, String inputJson,
                                             Long timeoutSec, StreamCallback callback) {
        return executeInSandbox(threadId, code, inputJson, timeoutSec, callback, "python");
    }

    private ExecutionResult executeInSandbox(String threadId, String code, String inputJson,
                                             Long timeoutSec, StreamCallback callback,
                                             String language) {
        String sessionId = null;
        boolean startedEmitted = false;
        try {
            sessionId = getOrCreateSession(threadId, language, timeoutSec);

            // Convert inputJson to raw stdin bytes — piped natively into the sandbox
            // process (docker exec -i / ProcessBuilder redirect), replacing the
            // legacy base64 sys.stdin shim. null/empty/"{}" → no stdin.
            byte[] stdinBytes = toStdinBytes(inputJson);

            // ── SANDBOX STARTED ──
            Map<String, Object> startedData = new LinkedHashMap<>();
            startedData.put("language", language);
            startedData.put("code", truncate(code, STARTED_CODE_PREVIEW_MAX));
            emitSandboxEvent(threadId, "STARTED", startedData);
            startedEmitted = true;

            // ── stream chunks ── composite callback: SSE stream + external callback
            final StreamCallback external = callback;
            StreamCallback sseCallback = (line, isError) -> {
                emitSandboxStreamChunks(threadId, line, isError);
                if (external != null) {
                    try {
                        external.onLine(line, isError);
                    } catch (Exception ignored) {
                        // never let an external callback break streaming
                    }
                }
            };

            ExecutionResult result = controlService.execute(sessionId, code, stdinBytes, sseCallback);

            // ── SANDBOX FINISHED ──
            emitSandboxFinished(threadId, result);
            // ── Audit (fire-and-forget) ──
            audit(threadId, sessionId, language, code, result);
            return result;
        } catch (Exception e) {
            log.error("[SandboxExec] Sandbox execution failed for thread {}: {}", threadId, e.getMessage());
            ExecutionResult errResult = ExecutionResult.error(
                    "Sandbox execution error: " + e.getMessage(), -1, 0, language, List.of());
            // If STARTED was emitted, close the event with an error FINISHED so the
            // frontend terminal doesn't hang in the "running" state.
            if (startedEmitted) {
                emitSandboxFinished(threadId, errResult);
            }
            // Audit the failed execution too.
            audit(threadId, sessionId, language, code, errResult);
            // If session creation failed, clear the cached session id so the next
            // call creates a fresh one.
            threadSessions.remove(threadId);
            return errResult;
        }
    }

    private String getOrCreateSession(String threadId, String language, Long timeoutSec) {
        return threadSessions.computeIfAbsent(threadId, tid -> {
            int timeout = (timeoutSec != null && timeoutSec > 0)
                    ? timeoutSec.intValue() : properties.getTimeout();
            return controlService.connect(language, timeout);
        });
    }

    /**
     * Convert the inputJson string to raw stdin bytes. Returns null when the input
     * is absent or the trivial {@code "{}"}, so the runtime skips the stdin pipe
     * entirely (identical to a no-stdin execution).
     */
    private static byte[] toStdinBytes(String inputJson) {
        if (inputJson == null || inputJson.isBlank() || "{}".equals(inputJson.trim())) {
            return null;
        }
        return inputJson.getBytes(StandardCharsets.UTF_8);
    }

    // ========================================================================
    //  Diagnostics
    // ========================================================================

    /** Current active thread-session count (for monitoring). */
    public int activeThreadSessionCount() {
        return threadSessions.size();
    }

    /** Whether a sandbox runtime is available (fail-closed when false). */
    public boolean isSandboxAvailable() {
        return runtimeFactory.isAvailable();
    }

    // ========================================================================
    //  Audit (Task 8)
    // ========================================================================

    /**
     * Persist an execution audit record. Fire-and-forget — failures are swallowed
     * inside {@link SandboxAuditService#recordAsync}. The {@code runtime} field is
     * resolved from the active runtime factory ("docker" / "local"); since the
     * legacy executor has been removed, "legacy" no longer appears.
     */
    private void audit(String threadId, String sessionId, String language,
                       String code, ExecutionResult result) {
        if (auditService == null) {
            return;
        }
        String runtime = runtimeFactory.isAvailable()
                ? runtimeFactory.selectedRuntimeId() : "none";
        try {
            auditService.recordAsync(threadId, sessionId, language, runtime, code, result);
        } catch (Exception e) {
            log.debug("[SandboxExec] audit dispatch failed: {}", e.getMessage());
        }
    }

    // ========================================================================
    //  SSE chunks streaming protocol
    // ========================================================================

    /**
     * Emit a {@code SANDBOX} SSE event of the given output type via the
     * {@link SandboxEventSink} abstraction. The sink implementation (provided by
     * the service module) handles the canonical event JSON construction
     * ({@code nodeName / outputType / messageType / sequenceNo / data}) and
     * pushes it to the per-thread reactor sink.
     * No-op when no sink is registered for the thread (e.g. REST manual execute path).
     */
    private void emitSandboxEvent(String threadId, String outputType, Map<String, Object> data) {
        if (eventSink == null) {
            return;
        }
        eventSink.emit(threadId, outputType, data);
    }

    /**
     * Emit one stdout/stderr line as one or more {@code output_type: stream} chunks.
     * Long lines are split at {@link #STREAM_CHUNK_MAX_LEN} characters.
     */
    private void emitSandboxStreamChunks(String threadId, String line, boolean isError) {
        if (line == null) {
            return;
        }
        if (line.isEmpty()) {
            emitSandboxEvent(threadId, "stream", streamChunkData(0, "", isError));
            return;
        }
        int index = 0;
        for (String chunk : chunkText(line, STREAM_CHUNK_MAX_LEN)) {
            emitSandboxEvent(threadId, "stream", streamChunkData(index, chunk, isError));
            index++;
        }
    }

    private Map<String, Object> streamChunkData(int index, String chunk, boolean isError) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("index", index);
        data.put("chunk", chunk);
        data.put("isError", isError);
        return data;
    }

    /**
     * Emit the terminal {@code SANDBOX FINISHED} event carrying the full
     * {@link com.sql.logic.engine.domain.sandbox.display.DisplayResult} projection
     * (stdout/stderr/exitCode/durationMs/status/files) so the frontend terminal can
     * render the final prompt line with exit code + timing.
     */
    private void emitSandboxFinished(String threadId, ExecutionResult result) {
        if (result == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", result.status() != null ? result.status().value() : "error");
        data.put("output", result.stdout() == null ? "" : result.stdout());
        data.put("error", result.stderr() == null ? "" : result.stderr());
        data.put("exitCode", result.exitCode());
        data.put("executionTimeMs", result.durationMs());
        data.put("timedOut", result.timedOut());
        data.put("language", result.language() == null ? "python" : result.language());
        if (result.displayResult() != null) {
            if (result.displayResult().files() != null) {
                data.put("files", result.displayResult().files());
            }
            // Propagate the VNC guiUrl so the frontend can render a noVNC iframe
            // instead of (or alongside) the terminal output.
            if (result.displayResult().guiUrl() != null && !result.displayResult().guiUrl().isBlank()) {
                data.put("guiUrl", result.displayResult().guiUrl());
            }
            // Propagate supplementary logs (e.g. pip install output) so the frontend
            // can render them in a collapsible section.
            if (result.displayResult().logs() != null && !result.displayResult().logs().isEmpty()) {
                data.put("logs", result.displayResult().logs());
            }
        }
        if (result.warnings() != null && !result.warnings().isEmpty()) {
            data.put("warnings", result.warnings());
        }
        emitSandboxEvent(threadId, "FINISHED", data);
    }

    /** Split {@code text} into chunks of at most {@code maxLen} characters. */
    private static List<String> chunkText(String text, int maxLen) {
        if (text == null || text.isEmpty() || maxLen <= 0) {
            return List.of(text == null ? "" : text);
        }
        if (text.length() <= maxLen) {
            return List.of(text);
        }
        List<String> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < text.length(); i += maxLen) {
            chunks.add(text.substring(i, Math.min(text.length(), i + maxLen)));
        }
        return chunks;
    }

    /** Truncate {@code s} to {@code maxLen} characters, appending an ellipsis if cut. */
    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) {
            return s == null ? "" : s;
        }
        return s.substring(0, maxLen) + "…";
    }
}
