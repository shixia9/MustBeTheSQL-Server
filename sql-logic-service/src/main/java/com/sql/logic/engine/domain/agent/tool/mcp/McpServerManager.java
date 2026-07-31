package com.sql.logic.engine.domain.agent.tool.mcp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.tool.ToolDefinition;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import com.sql.logic.engine.domain.agent.tool.ToolSource;
import com.sql.logic.engine.domain.agent.tool.ToolType;
import com.sql.logic.engine.infrastructure.dao.McpServerConfigDao;
import com.sql.logic.engine.infrastructure.po.McpServerConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages connections to external MCP servers.
 * <p>
 * On startup, reconnects to all active servers from the database.
 * Provides methods to add, remove, connect, and disconnect MCP servers.
 * Discovered tools are dynamically registered into {@link ToolRegistry}.
 * <p>
 * A {@code toolName -> serverId} map ({@link #toolToServer}) is maintained so
 * that {@link #callTool(String, Map, Long)} can route each tool invocation to
 * the exact server that owns it, instead of blindly iterating all transports.
 */
@Component
public class McpServerManager {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);

    private final McpServerConfigDao configDao;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Map<Long, McpTransport> activeTransports = new ConcurrentHashMap<>();

    /** Maps a registered tool name to the MCP server config id that owns it. */
    private final ConcurrentHashMap<String, Long> toolToServer = new ConcurrentHashMap<>();

    public McpServerManager(McpServerConfigDao configDao,
                           ToolRegistry toolRegistry,
                           ObjectMapper objectMapper) {
        this.configDao = configDao;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /** Create a new MCP server config, connect to it, and register its tools. */
    public McpServerConfig addServer(Long userId, String name, String transportType,
                                      String endpoint, Map<String, String> env) {
        McpServerConfig cfg = new McpServerConfig();
        cfg.setUserId(userId);
        cfg.setName(name);
        cfg.setTransportType(transportType.toUpperCase());
        cfg.setEndpoint(endpoint);
        if (env != null && !env.isEmpty()) {
            try { cfg.setEnvVars(objectMapper.writeValueAsString(env)); } catch (Exception ignored) {}
        }
        cfg.setStatus(1);
        Date now = new Date();
        cfg.setCreateTime(now);
        cfg.setUpdateTime(now);
        configDao.insert(cfg);

        try {
            connectAndRegister(cfg);
        } catch (Exception e) {
            log.warn("[McpServerManager] Server '{}' saved but connection failed: {}", name, e.getMessage());
        }
        return cfg;
    }

    /** Remove a server config and unregister its tools. */
    public void removeServer(Long configId) {
        disconnect(configId);
        configDao.deleteById(configId);
        log.info("[McpServerManager] Removed MCP server config id={}", configId);
    }

    /** Update an existing server config, disconnect and reconnect if transport changed. */
    public McpServerConfig updateServer(Long configId, String name, String transportType,
                                         String endpoint, Map<String, String> env) {
        McpServerConfig cfg = configDao.selectById(configId);
        if (cfg == null) throw new McpException("MCP server config not found: " + configId);
        boolean transportChanged = !cfg.getTransportType().equalsIgnoreCase(transportType)
                || !cfg.getEndpoint().equals(endpoint);
        cfg.setName(name);
        cfg.setTransportType(transportType.toUpperCase());
        cfg.setEndpoint(endpoint);
        if (env != null && !env.isEmpty()) {
            try { cfg.setEnvVars(objectMapper.writeValueAsString(env)); } catch (Exception ignored) {}
        } else {
            cfg.setEnvVars(null);
        }
        cfg.setUpdateTime(new Date());
        configDao.updateById(cfg);
        if (transportChanged) {
            disconnect(configId);
            try { connectAndRegister(cfg); } catch (Exception e) {
                log.warn("[McpServerManager] Server '{}' updated but reconnect failed: {}", name, e.getMessage());
            }
        }
        log.info("[McpServerManager] Updated MCP server id={}, name='{}'", configId, name);
        return cfg;
    }

    /** List all persisted MCP server configs for a user. */
    public List<McpServerConfig> listServers(Long userId) {
        return configDao.selectList(new QueryWrapper<McpServerConfig>()
                .eq("user_id", userId).orderByDesc("update_time"));
    }

    /** Connect to a server by its config ID and register its tools. */
    public void connectAndRegister(Long configId) {
        McpServerConfig cfg = configDao.selectById(configId);
        if (cfg == null || cfg.getStatus() == null || cfg.getStatus() != 1) return;
        connectAndRegister(cfg);
    }

    private void connectAndRegister(McpServerConfig cfg) {
        McpTransport transport = buildTransport(cfg);
        try {
            transport.connect();
            activeTransports.put(cfg.getId(), transport);

            // Discover tools
            String toolsJson = transport.sendRequest("tools/list", Map.of());
            JsonNode toolsNode = objectMapper.readTree(toolsJson);
            if (toolsNode != null && toolsNode.has("tools")) {
                int registered = 0;
                for (JsonNode toolNode : toolsNode.get("tools")) {
                    String toolName = toolNode.has("name") ? toolNode.get("name").asText() : null;
                    if (toolName == null) continue;
                    String displayName = toolNode.has("title") ? toolNode.get("title").asText() : toolName;
                    String desc = toolNode.has("description") ? toolNode.get("description").asText() : "";
                    String schema = toolNode.has("inputSchema") ? toolNode.get("inputSchema").toString() : null;
                    ToolType type = "SSE".equalsIgnoreCase(cfg.getTransportType())
                            ? ToolType.MCP_SSE : ToolType.MCP_STDIO;
                    ToolDefinition toolDef = new ToolDefinition(toolName, displayName, desc, type, schema,
                            cfg.getId(), cfg.getUserId(), ToolSource.MCP);
                    // Register under the owning user's scope (null userId -> public scope).
                    toolRegistry.register(cfg.getUserId(), toolDef);
                    // Record precise tool -> server routing.
                    toolToServer.put(toolName, cfg.getId());
                    registered++;
                }
                log.info("[McpServerManager] Registered {} tools from server '{}'", registered, cfg.getName());
            }
        } catch (Exception e) {
            log.warn("[McpServerManager] Failed to connect/register server '{}': {}", cfg.getName(), e.getMessage());
            try { transport.close(); } catch (Exception ignored) {}
            throw new McpException("Failed to connect to MCP server '" + cfg.getName() + "': " + e.getMessage(), e);
        }
    }

    /** Disconnect a server and unregister its tools. */
    public void disconnect(Long configId) {
        McpTransport transport = activeTransports.remove(configId);
        if (transport != null) {
            try { transport.close(); } catch (Exception ignored) {}
        }
        // Remove all tools owned by this server from the registry and the routing map.
        toolRegistry.unregisterByServer(configId);
        toolToServer.entrySet().removeIf(e -> configId != null && configId.equals(e.getValue()));
    }

    /** Check if a server is currently connected. */
    public boolean isConnected(Long configId) {
        McpTransport t = activeTransports.get(configId);
        return t != null && t.isConnected();
    }

    /**
     * Look up the registered {@link ToolDefinition} for a tool, respecting
     * user scope (user's private scope first, then public scope). Returns
     * {@code null} if the tool is not accessible by the user.
     * <p>
     * Used by the multi-agent path (e.g. {@code McpToolFixAction}) to obtain the
     * real tool metadata — including {@link ToolDefinition#parametersSchema()} —
     * without exposing the internal {@link ToolRegistry}.
     *
     * @param userId   owning user id, or {@code null} for a public/anonymous lookup
     * @param toolName the tool name to look up
     * @return the matching tool definition, or {@code null} if not found
     */
    public ToolDefinition getToolDefinition(Long userId, String toolName) {
        return toolRegistry.getTool(userId, toolName);
    }

    /**
     * Call a tool registered by any connected MCP server.
     * <p>
     * Backward-compatible overload (single-agent path): routes precisely via the
     * {@link #toolToServer} mapping without user-scope verification. Delegates to
     * {@link #callTool(String, Map, Long)} with {@code userId=null}.
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        return callTool(toolName, arguments, null);
    }

    /**
     * Call a tool, routing the request to the exact MCP server that owns it.
     * <p>
     * The owning server is resolved from {@link #toolToServer}; if no mapping
     * exists an {@link McpException} is thrown (no blind iteration). When
     * {@code userId} is non-null, the tool's user-scope ownership is verified
     * via {@link ToolRegistry#getTool(Long, String)} before the call is dispatched.
     *
     * @param toolName  name of the MCP tool to invoke
     * @param arguments tool arguments as a key-value map
     * @param userId    owning user id, or {@code null} for an anonymous/public call
     * @return the tool result content as a string
     * @throws McpException if the tool is not found, the server is not connected,
     *                      the user does not own the tool, or the call fails
     */
    public String callTool(String toolName, Map<String, Object> arguments, Long userId) {
        // Resolve the owning server. For user-scoped calls, the user's own
        // ToolDefinition is the authoritative source of serverId — this avoids
        // the global {@link #toolToServer} map colliding when two users (or two
        // of a user's servers) expose a tool with the same name, which would
        // otherwise route the call to the last-registered server. For anonymous
        // calls (single-agent path, userId=null) we fall back to toolToServer
        // since there is no user scope to consult.
        Long serverId;
        if (userId != null) {
            ToolDefinition def = toolRegistry.getTool(userId, toolName);
            if (def == null) {
                throw new McpException("MCP tool not found in user scope: " + toolName);
            }
            serverId = def.serverId();
            if (serverId == null) {
                // The tool exists in the user's scope but is not an MCP tool
                // (e.g. a BUILTIN tool). It cannot be routed to an MCP server.
                throw new McpException("Tool is not an MCP tool: " + toolName);
            }
        } else {
            serverId = toolToServer.get(toolName);
            if (serverId == null) {
                throw new McpException("MCP tool not found: " + toolName);
            }
        }
        McpTransport transport = activeTransports.get(serverId);
        if (transport == null || !transport.isConnected()) {
            throw new McpException("MCP server not connected: " + serverId);
        }
        try {
            String result = transport.sendRequest("tools/call", Map.of(
                    "name", toolName,
                    "arguments", arguments
            ));
            if (result == null) {
                return null;
            }
            // Extract content from MCP response
            JsonNode node = objectMapper.readTree(result);
            if (node.has("content") && node.get("content").isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode c : node.get("content")) {
                    if (c.has("text")) sb.append(c.get("text").asText());
                }
                return sb.toString();
            }
            return result;
        } catch (McpException e) {
            throw e;
        } catch (Exception e) {
            throw new McpException("Tool call '" + toolName + "' failed on server " + serverId
                    + ": " + e.getMessage(), e);
        }
    }

    private McpTransport buildTransport(McpServerConfig cfg) {
        if ("SSE".equalsIgnoreCase(cfg.getTransportType())) {
            return new McpSseTransport(cfg.getEndpoint(), objectMapper);
        } else if ("STDIO".equalsIgnoreCase(cfg.getTransportType())) {
            return new McpStdioTransport(cfg.getEndpoint(), parseEnvVars(cfg.getEnvVars()), objectMapper);
        }
        throw new McpException("Unsupported transport type: " + cfg.getTransportType());
    }

    /**
     * Parse the {@code envVars} JSON string stored on {@link McpServerConfig}
     * into a {@code Map<String,String>} suitable for
     * {@link ProcessBuilder#environment()}. Returns an empty map when envVars
     * is null/blank or fails to parse (a warn is logged on parse failure).
     */
    private Map<String, String> parseEnvVars(String envVars) {
        if (envVars == null || envVars.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(envVars);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            Map<String, String> result = new java.util.HashMap<>();
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                result.put(e.getKey(), v != null && !v.isNull() ? v.asText() : "");
            });
            return result;
        } catch (Exception e) {
            log.warn("[McpServerManager] Failed to parse envVars for server config: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * On startup, reconnect to all MCP servers marked active (status=1) in the
     * database. Each server is reconnected independently; a failure for one
     * server is logged as a warning and does not block startup or affect the
     * others. A summary of the reconnection is logged at the end.
     */
    @PostConstruct
    public void reconnectAll() {
        List<McpServerConfig> active = configDao.selectList(
                new QueryWrapper<McpServerConfig>().eq("status", 1));
        if (active == null || active.isEmpty()) {
            log.info("[McpServerManager] No active MCP servers to reconnect");
            return;
        }
        int ok = 0;
        for (McpServerConfig cfg : active) {
            try {
                connectAndRegister(cfg);
                ok++;
            } catch (Exception e) {
                log.warn("[McpServerManager] Reconnect failed for server '{}': {}",
                        cfg.getName(), e.getMessage());
            }
        }
        log.info("[McpServerManager] Reconnected {}/{} active MCP servers", ok, active.size());
    }

    @PreDestroy
    void shutdown() {
        activeTransports.forEach((id, transport) -> {
            try { transport.close(); } catch (Exception ignored) {}
        });
        activeTransports.clear();
        toolToServer.clear();
    }
}
