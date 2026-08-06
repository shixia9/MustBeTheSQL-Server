package com.sql.logic.engine.domain.sandbox.execution;

/**
 * Callback for streaming sandbox stdout/stderr line-by-line.
 *
 * <p>Implementations receive each output line as it is produced, enabling the
 * frontend terminal panel to append in real time instead of waiting for the
 * whole execution to finish. Pass {@code null} (or use the no-callback
 * {@code execute(code)} overload) for non-streaming batch execution.
 *
 * <p>The {@code isError} flag distinguishes stdout lines ({@code false}) from
 * stderr lines ({@code true}) so the terminal renderer can colour them
 * differently (stdout grey, stderr red).
 */
@FunctionalInterface
public interface StreamCallback {

    /**
     * Called for each line of output produced by the sandbox process.
     *
     * @param line    one line of stdout or stderr (without the trailing newline)
     * @param isError {@code true} if the line came from stderr, {@code false} for stdout
     */
    void onLine(String line, boolean isError);
}
