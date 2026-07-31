package com.sql.logic.engine.domain.agent.tool.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final long REQUEST_TIMEOUT_MS = 30000L;

    private final String command;
    private final Map<String, String> envVars;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong nextId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final Object stdinLock = new Object();

    private Process process;
    private BufferedWriter stdin;
    private Thread readerThread;
    private volatile boolean closing = false;

    public McpStdioTransport(String command, Map<String, String> envVars, ObjectMapper objectMapper) {
        this.command = command;
        this.envVars = envVars;
        this.objectMapper = objectMapper;
    }

    @Override
    public void connect() {
        try {
            String[] parts = command.split("\\s+", 2);
            ProcessBuilder pb;
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWin && parts.length == 2) {
                pb = new ProcessBuilder("cmd.exe", "/c", "\"" + parts[0] + "\" " + parts[1]);
            } else if (isWin) {
                pb = new ProcessBuilder("cmd.exe", "/c", "\"" + parts[0] + "\"");
            } else if (parts.length == 2) {
                pb = new ProcessBuilder(parts[0], parts[1]);
            } else {
                pb = new ProcessBuilder(parts[0]);
            }
            pb.redirectErrorStream(false);
            if (envVars != null && !envVars.isEmpty()) {
                pb.environment().putAll(envVars);
            }
            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            // Start a SINGLE dedicated daemon reader thread that dispatches
            // responses to the matching pending request by JSON-RPC id.
            String threadName = "mcp-stdout-" + command.substring(0, Math.min(20, command.length()));
            readerThread = new Thread(this::readerLoop, threadName);
            readerThread.setDaemon(true);
            readerThread.start();

            // Capture stderr in a background thread for diagnostics
            Thread stderrReader = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        log.debug("[McpStdioTransport stderr] {}", line);
                    }
                } catch (Exception ignored) {
                    // stream closed on process exit
                }
            }, "mcp-stderr-" + command.substring(0, Math.min(20, command.length())));
            stderrReader.setDaemon(true);
            stderrReader.start();

            // Send initialize request and wait for its response (dispatched by reader thread)
            String initResp = sendRequest("initialize", Map.of(
                    "protocolVersion", "0.1.0",
                    "capabilities", Map.of(),
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

    /**
     * Reader loop: continuously reads lines from the child process stdout,
     * parses each as a JSON-RPC response, extracts the {@code id} field, and
     * completes the matching pending {@link CompletableFuture}. Lines without
     * an {@code id} (notifications) or that fail to parse are logged and skipped.
     * When stdout closes (process exit), any still-pending requests are failed.
     */
    private void readerLoop() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closing && (line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                dispatchResponse(line);
            }
        } catch (Exception e) {
            if (!closing) {
                log.warn("[McpStdioTransport] stdout reader terminated: {}", e.getMessage());
            }
        } finally {
            // Fail any still-pending requests so callers don't hang forever
            McpException closed = new McpException("MCP stdio process closed before response");
            for (CompletableFuture<String> f : pending.values()) {
                f.completeExceptionally(closed);
            }
            pending.clear();
        }
    }

    private void dispatchResponse(String line) {
        JsonNode root;
        try {
            root = objectMapper.readTree(line);
        } catch (Exception e) {
            log.debug("[McpStdioTransport] Unparsable stdout line (skipped): {} ({})", line, e.getMessage());
            return;
        }
        JsonNode idNode = root.get("id");
        if (idNode == null || idNode.isNull()) {
            // Notification (no id) — log and skip
            log.debug("[McpStdioTransport] ← notification: {}", line);
            return;
        }
        long id;
        if (idNode.isNumber()) {
            id = idNode.asLong();
        } else {
            try {
                id = Long.parseLong(idNode.asText());
            } catch (NumberFormatException nfe) {
                log.warn("[McpStdioTransport] Cannot parse id from response (skipped): {}", line);
                return;
            }
        }
        CompletableFuture<String> future = pending.remove(id);
        if (future == null) {
            log.warn("[McpStdioTransport] No pending request for id={} (response: {})", id, line);
            return;
        }
        if (root.has("error")) {
            JsonNode err = root.get("error");
            String msg = err.has("message") ? err.get("message").asText() : err.toString();
            future.completeExceptionally(new McpException("MCP JSON-RPC error: " + msg));
        } else {
            JsonNode result = root.get("result");
            future.complete(result != null ? result.toString() : null);
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get() && process != null && process.isAlive();
    }

    @Override
    public String sendRequest(String method, Map<String, Object> params) throws McpException {
        if (process == null || !process.isAlive()) {
            throw new McpException("MCP stdio transport is not connected");
        }
        long id = nextId.getAndIncrement();
        CompletableFuture<String> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            Map<String, Object> rpc = new LinkedHashMap<>();
            rpc.put("jsonrpc", "2.0");
            rpc.put("method", method);
            rpc.put("params", params);
            rpc.put("id", id);
            String requestJson = objectMapper.writeValueAsString(rpc);

            log.debug("[McpStdioTransport] → {} (id={}) {}", method, id, params.keySet());
            synchronized (stdinLock) {
                if (stdin == null) {
                    throw new McpException("MCP stdio transport stdin is closed");
                }
                stdin.write(requestJson);
                stdin.write("\n");
                stdin.flush();
            }

            try {
                return future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                pending.remove(id);
                future.cancel(true);
                throw new McpException("No response from MCP stdio process for " + method + " within 30s");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof McpException) {
                    throw (McpException) cause;
                }
                throw new McpException("MCP stdio request '" + method + "' failed: "
                        + (cause != null ? cause.getMessage() : e.getMessage()),
                        cause != null ? cause : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.remove(id);
                future.cancel(true);
                throw new McpException("MCP stdio request '" + method + "' interrupted", e);
            }
        } catch (McpException e) {
            pending.remove(id);
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            throw new McpException("MCP stdio request '" + method + "' failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        closing = true;
        connected.set(false);
        // Fail any pending requests so waiting callers don't hang
        McpException closed = new McpException("MCP stdio transport closed");
        for (CompletableFuture<String> f : pending.values()) {
            f.completeExceptionally(closed);
        }
        pending.clear();
        synchronized (stdinLock) {
            if (stdin != null) {
                try { stdin.close(); } catch (Exception ignored) {}
            }
        }
        if (process != null) {
            try {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("[McpStdioTransport] Process terminated: {}", command);
        }
        if (readerThread != null) {
            readerThread.interrupt();
        }
    }
}
