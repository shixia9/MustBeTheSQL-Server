package com.sql.logic.engine.domain.agent.tool.mcp;

/**
 * Thrown when an MCP transport or JSON-RPC call fails.
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
