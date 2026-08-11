package com.sql.logic.engine.domain.sandbox.display;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Display-layer result.
 *
 * <p>Encapsulates everything the frontend terminal panel needs to render one
 * execution: stdout/stderr, exit code, timing, produced files, and optional
 * GUI/VNC fields (reserved for future VNC sandbox support, not implemented in
 * this phase).
 *
 * <p>This is the canonical result object returned by {@code SandboxSession.execute()}
 * and surfaced through the REST API + SSE chunks protocol. It is intentionally
 * a Java {@code record} so it serialises cleanly to JSON via Jackson.
 *
 * @param status          "success" or "error" (string, matching DB-GPT)
 * @param output          stdout text (may be empty)
 * @param error           stderr text (may be empty)
 * @param executionTimeMs wall-clock duration of the execution in milliseconds
 * @param exitCode        process exit code (0 = success; -1 = never started / killed)
 * @param files           list of filenames produced inside the sandbox working dir
 * @param logs            supplementary log lines (e.g. dependency install output)
 * @param guiFrame        optional GUI frame payload (VNC; reserved, null for now)
 * @param guiUrl          optional GUI URL (VNC; reserved, null for now)
 * @param screenshots     optional base64 screenshot list (VNC; reserved, empty for now)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisplayResult(
        String status,
        String output,
        String error,
        long executionTimeMs,
        int exitCode,
        List<String> files,
        List<String> logs,
        Object guiFrame,
        String guiUrl,
        List<String> screenshots
) {
    private static final List<String> EMPTY = List.of();

    public DisplayResult {
        if (files == null) files = EMPTY;
        if (logs == null) logs = EMPTY;
        if (screenshots == null) screenshots = EMPTY;
    }

    /** Successful execution with stdout, timing, exit code, and produced files. */
    public static DisplayResult success(String output, long executionTimeMs, int exitCode, List<String> files) {
        return new DisplayResult("success", output == null ? "" : output, "",
                executionTimeMs, exitCode, files, EMPTY, null, null, EMPTY);
    }

    /** Successful execution with stdout, timing, exit code, files, and supplementary logs. */
    public static DisplayResult success(String output, long executionTimeMs, int exitCode,
                                        List<String> files, List<String> logs) {
        return new DisplayResult("success", output == null ? "" : output, "",
                executionTimeMs, exitCode, files, logs == null ? EMPTY : logs, null, null, EMPTY);
    }

    /** Successful execution with stdout and stderr (e.g. non-zero exit but still ran). */
    public static DisplayResult of(String status, String output, String error,
                                   long executionTimeMs, int exitCode, List<String> files) {
        return new DisplayResult(status, output == null ? "" : output, error == null ? "" : error,
                executionTimeMs, exitCode, files, EMPTY, null, null, EMPTY);
    }

    /** Full-arity factory with logs (e.g. dependency install output passthrough). */
    public static DisplayResult of(String status, String output, String error,
                                   long executionTimeMs, int exitCode, List<String> files,
                                   List<String> logs) {
        return new DisplayResult(status, output == null ? "" : output, error == null ? "" : error,
                executionTimeMs, exitCode, files, logs == null ? EMPTY : logs, null, null, EMPTY);
    }

    /** Failed execution — never ran or crashed before producing output. */
    public static DisplayResult error(String error, int exitCode) {
        return new DisplayResult("error", "", error == null ? "" : error,
                0, exitCode, EMPTY, EMPTY, null, null, EMPTY);
    }

    /**
     * VNC/GUI session result — the GUI container is running and accessible via
     * {@code guiUrl}. Aligns with DB-GPT's VNC execute() short-circuit: no code
     * is run; the container's {@code /startup.sh} launches the VNC server and the
     * frontend renders a noVNC iframe pointed at {@code guiUrl}.
     *
     * @param guiUrl          the web-accessible VNC URL (e.g. {@code http://localhost:6080/vnc.html})
     * @param executionTimeMs time spent resolving the port (typically ~0)
     */
    public static DisplayResult vnc(String guiUrl, long executionTimeMs) {
        return new DisplayResult("success", "GUI container started", "", executionTimeMs, 0,
                EMPTY, EMPTY, null, guiUrl, EMPTY);
    }

    public boolean isSuccess() {
        return "success".equalsIgnoreCase(status);
    }
}
