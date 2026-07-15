package com.sql.logic.engine.domain.agent.tool.mcp;

import java.util.Map;

/**
 * Transport abstraction for MCP (Model Context Protocol) communication.
 * <p>
 * Supports two implementations:
 * <ul>
 *   <li>{@code McpSseTransport} — HTTP SSE transport (connect to a remote MCP server)</li>
 *   <li>{@code McpStdioTransport} — stdio transport (spawn a local MCP server process)</li>
 * </ul>
 */
public interface McpTransport extends AutoCloseable {

    /** Establish the connection to the MCP server. */
    void connect();

    /** True if the transport is currently connected and ready for requests. */
    boolean isConnected();

    /**
     * Send a JSON-RPC request to the MCP server and return the result as a raw
     * JSON string (parsed from the response).
     *
     * @param method MCP method name (e.g. "tools/list", "tools/call")
     * @param params method parameters as key-value map
     * @return the "result" field of the JSON-RPC response as a string
     * @throws McpException if the server returns an error or the transport fails
     */
    String sendRequest(String method, Map<String, Object> params) throws McpException;

    /** Close the connection and release resources. */
    @Override
    void close();
}
