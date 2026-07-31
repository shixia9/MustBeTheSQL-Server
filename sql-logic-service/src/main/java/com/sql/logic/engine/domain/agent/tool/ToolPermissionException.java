package com.sql.logic.engine.domain.agent.tool;

/**
 * Thrown when a tool invocation is denied because the tool is not accessible
 * by the requesting user scope (neither the user's private scope nor the
 * public scope). This enforces user-level isolation for MCP/SKILL tools.
 */
public class ToolPermissionException extends RuntimeException {

    public ToolPermissionException(String message) {
        super(message);
    }

    public ToolPermissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
