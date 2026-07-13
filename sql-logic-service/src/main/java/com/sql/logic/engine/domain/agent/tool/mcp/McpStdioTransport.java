package com.sql.logic.engine.domain.agent.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP transport over stdio (spawn a local MCP server process).
 * <p>
 * Starts a child process using the configured {@code command} and
 * communicates via stdin/stdout using newline-delimited JSON-RPC.
 * <p>
 * Supported command formats:
 * <ul>
 *   <li>{@code "python mcp_server.py"} — split on first space to get program + args</li>
 *   <li>{@code "node dist/server.js"} — same pattern</li>
 *   <li>{@code "npx @some/mcp-server"} — same pattern</li>
 * </ul>
 */
public class McpStdioTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpStdioTransport.class);

    private final String command;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private Process process;
    private Scanner stdout;
    private OutputStream stdin;

    public McpStdioTransport(String command, ObjectMapper objectMapper) {
        this.command = command;
        this.objectMapper = objectMapper;
    }

    @Override
    public void connect() {
        try {
            String[] parts = command.split("\\s+", 2);
            ProcessBuilder pb;
            if (parts.length == 2) {
                pb = new ProcessBuilder(parts[0], parts[1]);
            } else {
                pb = new ProcessBuilder(parts[0]);
            }
            pb.redirectErrorStream(false);
            process = pb.start();
            stdout = new Scanner(process.getInputStream(), StandardCharsets.UTF_8);
            stdin = process.getOutputStream();

            // Send initialize request and read response
            String initResp = sendRequest("initialize", Map.of(
                    "protocolVersion", "0.1.0",
                    "clientInfo", Map.of("name", "SQL-Logic-Engine", "version", "2.0")
            ));
            connected.set(true);
            log.info("[McpStdioTransport] Connected to '{}' — initialized: {}", command,
                    initResp != null ? initResp.substring(0, Math.min(80, initResp.length())) : "null");
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Failed to start MCP stdio process '" + command + "': " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get() && process != null && process.isAlive();
    }

    @Override
    public String sendRequest(String method, Map<String, Object> params) throws McpException {
        if (!isConnected()) {
            throw new McpException("MCP stdio transport is not connected");
        }
        try {
            String requestId = UUID.randomUUID().toString().substring(0, 8);
            Map<String, Object> rpc = new java.util.LinkedHashMap<>();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", method);
            rpc.put("params", params);
            rpc.put("id", requestId);
            String requestJson = objectMapper.writeValueAsString(rpc);

            log.debug("[McpStdioTransport] → {} {}", method, params.keySet());
            stdin.write((requestJson + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();

            // Read response line (with timeout)
            String line = null;
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                if (stdout.hasNextLine()) {
                    line = stdout.nextLine();
                    break;
                }
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
            if (line == null || line.isBlank()) {
                throw new McpException("No response from MCP stdio process for " + method + " within 30s");
            }

            JsonNode root = objectMapper.readTree(line);
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
            throw new McpException("MCP stdio request '" + method + "' failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        connected.set(false);
        if (process != null) {
            try {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("[McpStdioTransport] Process terminated: {}", command);
        }
    }
}
