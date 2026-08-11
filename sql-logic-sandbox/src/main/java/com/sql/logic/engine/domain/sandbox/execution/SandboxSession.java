package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.display.DisplayResult;

import java.util.List;
import java.util.Map;

/**
 * One sandbox execution session.
 *
 * <p>A session is a stateful execution context: in the Docker runtime it is
 * backed by a long-lived container ({@code tail -f /dev/null}); in the local
 * runtime it is a persistent working directory + process pool. Multiple
 * {@code execute()} calls within the same session share state (installed
 * dependencies, files, environment).
 *
 * <p>Lifecycle: {@code start()} → ( {@code execute()} / {@code installDependencies()}
 * / {@code getFileContent()} )&ast; → {@code stop()}.
 */
public interface SandboxSession {

    // ---- Identity & metadata ----

    /** Unique session identifier: used as the Docker container name suffix. */
    String sessionId();

    /** The configuration this session was created with. */
    SessionConfig config();

    /** Epoch millis when the session was created. */
    long createdAt();

    /** Epoch millis of the last {@code execute()} / {@code installDependencies()} call. */
    long lastAccessed();

    /** Refresh {@link #lastAccessed()} to now (called by implementations on each access). */
    void updateLastAccessed();

    /** Whether the session is started and ready to accept executions. */
    boolean isActive();

    // ---- Lifecycle ----

    /** Start the session (create container / working dir). Returns true on success. */
    boolean start();

    /** Stop the session (destroy container / clean working dir). Returns true on success. */
    boolean stop();

    // ---- Execution ----

    /**
     * Execute code in the sandbox (non-streaming batch mode).
     *
     * @param code the source code to execute
     * @return the execution result (never null)
     */
    ExecutionResult execute(String code);

    /**
     * Execute code in the sandbox with line-by-line streaming output.
     *
     * @param code     the source code to execute
     * @param callback receives each stdout/stderr line as it is produced (may be null)
     * @return the execution result (never null)
     */
    ExecutionResult execute(String code, StreamCallback callback);

    /**
     * Execute code in the sandbox with optional stdin bytes piped to the process.
     *
     * <p>This replaces the legacy base64 {@code sys.stdin} shim: the script reads
     * {@code sys.stdin} natively, and the runtime pipes {@code stdin} into the
     * process (Docker: {@code docker exec -i}; Local: {@code ProcessBuilder}
     * input redirect). When {@code stdin} is null/empty, behaviour is identical
     * to {@link #execute(String, StreamCallback)}.
     *
     * @param code     the source code to execute
     * @param stdin    raw stdin bytes (e.g. the inputJson); null for no stdin
     * @param callback receives each stdout/stderr line as it is produced (may be null)
     * @return the execution result (never null)
     */
    default ExecutionResult execute(String code, byte[] stdin, StreamCallback callback) {
        return execute(code, callback);
    }

    /**
     * Install dependencies inside the session (persisted for subsequent executions).
     * Python → {@code pip install}; JavaScript → {@code npm install}.
     *
     * @param dependencies list of package specifiers (e.g. ["pandas", "scikit-learn"])
     * @return the installation result
     */
    ExecutionResult installDependencies(List<String> dependencies);

    /**
     * Retrieve a file produced inside the sandbox working directory.
     *
     * @param filename name of the file (relative to the session working dir)
     * @return the file content as a DisplayResult, or null if unavailable
     */
    DisplayResult getFileContent(String filename);

    /**
     * Get the current session status (runtime-specific: container state, process
     * list, memory usage, etc.).
     */
    Map<String, Object> getStatus();

    /**
     * Apply runtime configuration overrides (environment variables, timeout).
     * Called by the control layer when a {@code /configure} request includes
     * {@code configInfo}. The default implementation is a no-op — runtimes that
     * support dynamic reconfiguration (e.g. container CLI) override this.
     *
     * @param envOverrides    additional environment variables for subsequent executions
     * @param timeoutOverride new per-execution timeout in seconds (null = no change)
     */
    default void applyConfig(Map<String, String> envOverrides, Integer timeoutOverride) {
        // no-op by default
    }

    /**
     * Collect runtime resource statistics (memory usage, CPU usage, etc.).
     * Called by the control layer's {@code status()} method. The default
     * implementation returns an empty map — runtimes that can probe resource
     * usage (e.g. {@code docker stats}) override this.
     *
     * @return a map with keys like "memoryUsage", "cpuUsage" (empty if unsupported)
     */
    default Map<String, Object> collectStats() {
        return Map.of();
    }
}
