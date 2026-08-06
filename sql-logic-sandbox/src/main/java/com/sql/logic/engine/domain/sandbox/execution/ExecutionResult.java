package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.display.DisplayResult;

import java.util.List;

/**
 * Execution-layer result.
 *
 * <p>This is what {@code SandboxSession.execute()} returns to the control /
 * domain layer. The {@link #displayResult()} field carries the frontend-facing
 * projection (stdout/stderr/files/gui) so callers don't need to rebuild it.
 *
 * @param status            terminal status of the execution
 * @param stdout            raw stdout (same as {@code displayResult.output()})
 * @param stderr            raw stderr (same as {@code displayResult.error()})
 * @param exitCode          process exit code (0 = success; -1 = never started / killed)
 * @param durationMs        wall-clock execution duration in milliseconds
 * @param timedOut          true if the execution was killed because it exceeded the timeout
 * @param memoryUsageBytes  peak memory usage observed (bytes); 0 if unavailable
 * @param language          the language key used for this execution (e.g. "python")
 * @param displayResult     the display-layer projection for the frontend / REST API
 * @param warnings          security warnings collected (non-blocking); empty if clean
 */
public record ExecutionResult(
        ExecutionStatus status,
        String stdout,
        String stderr,
        int exitCode,
        long durationMs,
        boolean timedOut,
        long memoryUsageBytes,
        String language,
        DisplayResult displayResult,
        List<String> warnings
) {
    private static final List<String> EMPTY = List.of();

    public ExecutionResult {
        if (warnings == null) warnings = EMPTY;
    }

    // ---- Factory methods ----

    public static ExecutionResult success(String stdout, String stderr, int exitCode, long durationMs,
                                          String language, List<String> files, List<String> warnings) {
        DisplayResult display = DisplayResult.of("success", stdout, stderr, durationMs, exitCode, files);
        return new ExecutionResult(ExecutionStatus.SUCCESS, stdout == null ? "" : stdout,
                stderr == null ? "" : stderr, exitCode, durationMs, false, 0, language,
                display, warnings == null ? EMPTY : warnings);
    }

    /**
     * Success factory with supplementary logs (e.g. dependency install output).
     * The {@code logs} are passed through to the {@link DisplayResult} so the
     * frontend can render them in a collapsible section.
     */
    public static ExecutionResult success(String stdout, String stderr, int exitCode, long durationMs,
                                          String language, List<String> files, List<String> logs,
                                          List<String> warnings) {
        DisplayResult display = DisplayResult.of("success", stdout, stderr, durationMs, exitCode, files, logs);
        return new ExecutionResult(ExecutionStatus.SUCCESS, stdout == null ? "" : stdout,
                stderr == null ? "" : stderr, exitCode, durationMs, false, 0, language,
                display, warnings == null ? EMPTY : warnings);
    }

    public static ExecutionResult error(String stderr, int exitCode, long durationMs,
                                        String language, List<String> warnings) {
        DisplayResult display = DisplayResult.of("error", "", stderr, durationMs, exitCode, List.of());
        return new ExecutionResult(ExecutionStatus.ERROR, "", stderr == null ? "" : stderr,
                exitCode, durationMs, false, 0, language, display, warnings == null ? EMPTY : warnings);
    }

    public static ExecutionResult timeout(long durationMs, String language, String message) {
        DisplayResult display = DisplayResult.error(message, -1);
        return new ExecutionResult(ExecutionStatus.TIMEOUT, "", message, -1, durationMs,
                true, 0, language, display, EMPTY);
    }

    public static ExecutionResult resourceLimit(String message, String language) {
        DisplayResult display = DisplayResult.error(message, -1);
        return new ExecutionResult(ExecutionStatus.RESOURCE_LIMIT, "", message, -1, 0,
                false, 0, language, display, EMPTY);
    }

    /**
     * VNC/GUI session short-circuit — the GUI container is already running with
     * {@code /startup.sh}; {@code execute()} returns immediately with a
     * {@code guiUrl} instead of running code.
     *
     * @param guiUrl    the web-accessible VNC URL
     * @param durationMs time spent resolving the port
     * @param language  the VNC language key (e.g. "python-vnc")
     * @return a success ExecutionResult whose displayResult carries the guiUrl
     */
    public static ExecutionResult vnc(String guiUrl, long durationMs, String language) {
        DisplayResult display = DisplayResult.vnc(guiUrl, durationMs);
        return new ExecutionResult(ExecutionStatus.SUCCESS, "GUI container started", "",
                0, durationMs, false, 0, language, display, EMPTY);
    }

    public boolean isSuccess() {
        return status == ExecutionStatus.SUCCESS;
    }
}
