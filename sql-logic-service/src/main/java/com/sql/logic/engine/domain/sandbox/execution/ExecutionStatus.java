package com.sql.logic.engine.domain.sandbox.execution;

/**
 * Sandbox execution status.
 */
public enum ExecutionStatus {

    // code ran and exited 0.
    SUCCESS("success"),
    // code ran but exited non-zero, or failed to start.
    ERROR("error"),
    // execution exceeded the configured timeout and was killed.
    TIMEOUT("timeout"),
    // a resource limit (memory / processes / file size) was breached.
    RESOURCE_LIMIT("resource_limit");

    private final String value;

    ExecutionStatus(String value) {
        this.value = value;
    }

    /** Wire value. */
    public String value() {
        return value;
    }

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
