package com.sql.logic.engine.domain.agent.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP transport over HTTP SSE (Server-Sent Events).
 * <p>
 * Connects to a remote MCP server via HTTP. Requests are sent as HTTP POST with
 * JSON-RPC body; responses are streamed back via SSE, identified by the matching
 * {@code id} field.
 */
public class McpSseTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpSseTransport.class);

    private final String endpoint;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public McpSseTransport(String endpoint, ObjectMapper objectMapper) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.objectMapper = objectMapper;
    }

    @Override
    public void connect() {
        // SSE transport is connectionless per-request; validate reachability with a ping
        try {
            HttpURLConnection conn = openConnection("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            String body = jsonRpcRequest("ping", Map.of());
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status >= 200 && status < 500) {
                connected.set(true);
                log.info("[McpSseTransport] Connected to {}", endpoint);
            } else {
                throw new McpException("MCP server returned HTTP " + status + " on connect");
            }
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Failed to connect to MCP SSE server at " + endpoint + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public String sendRequest(String method, Map<String, Object> params) throws McpException {
        try {
            String requestId = UUID.randomUUID().toString().substring(0, 8);
            String body = jsonRpcRequest(method, params, requestId);
            log.debug("[McpSseTransport] → {} {}", method, params.keySet());

            HttpURLConnection conn = openConnection("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            if (status != 200) {
                String err = readErrorBody(conn);
                throw new McpException("MCP server returned HTTP " + status + " for " + method + ": " + err);
            }

            JsonNode root = objectMapper.readTree(conn.getInputStream());
            if (root.has("error")) {
                JsonNode err = root.get("error");
                String msg = err.has("message") ? err.get("message").asText() : err.toString();
                throw new McpException("MCP JSON-RPC error for " + method + ": " + msg);
            }
            JsonNode result = root.get("result");
            return result != null ? objectMapper.writeValueAsString(result) : null;
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("MCP request '" + method + "' failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        connected.set(false);
        log.info("[McpSseTransport] Disconnected from {}", endpoint);
    }

    private HttpURLConnection openConnection(String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        return conn;
    }

    private String jsonRpcRequest(String method, Map<String, Object> params) {
        return jsonRpcRequest(method, params, null);
    }

    private String jsonRpcRequest(String method, Map<String, Object> params, String id) {
        try {
            Map<String, Object> rpc = new java.util.LinkedHashMap<>();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", method);
            rpc.put("params", params);
            rpc.put("id", id != null ? id : UUID.randomUUID().toString().substring(0, 8));
            return objectMapper.writeValueAsString(rpc);
        } catch (Exception e) {
            throw new McpException("Failed to build JSON-RPC request: " + e.getMessage(), e);
        }
    }

    private String readErrorBody(HttpURLConnection conn) {
        try {
            return new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(unable to read error body)";
        }
    }
}
