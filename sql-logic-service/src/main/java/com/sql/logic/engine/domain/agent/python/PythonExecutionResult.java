package com.sql.logic.engine.domain.agent.python;

/**
 * Result of executing a Python script in the sandbox.
 * <p>
 * {@code output} holds stdout (the script's JSON analysis result on success);
 * {@code error} holds stderr (traceback or warnings). A run is considered successful
 * iff {@code success=true} — in which case {@code output} contains valid JSON even if
 * stderr carries non-fatal FutureWarnings.
 */
public record PythonExecutionResult(
        boolean success,
        String output,
        String error
) {
    public static PythonExecutionResult ok(String output, String error) {
        return new PythonExecutionResult(true, output, error);
    }

    public static PythonExecutionResult fail(String output, String error) {
        return new PythonExecutionResult(false, output, error);
    }
}