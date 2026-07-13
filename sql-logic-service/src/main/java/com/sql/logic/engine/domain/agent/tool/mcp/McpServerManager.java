package com.sql.logic.engine.domain.agent.tool.mcp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.tool.ToolDefinition;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import com.sql.logic.engine.domain.agent.tool.ToolType;
import com.sql.logic.engine.infrastructure.dao.McpServerConfigDao;
import com.sql.logic.engine.infrastructure.po.McpServerConfig;
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
 */
@Component
public class McpServerManager {

    private static final Logger log = LoggerFactory.getLogger(McpServerManager.class);

    private final McpServerConfigDao configDao;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Map<Long, McpTransport> activeTransports = new ConcurrentHashMap<>();

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
                    toolRegistry.register(new ToolDefinition(toolName, displayName, desc, type, schema));
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
            transport.close();
        }
    }

    /** Check if a server is currently connected. */
    public boolean isConnected(Long configId) {
        McpTransport t = activeTransports.get(configId);
        return t != null && t.isConnected();
    }

    private McpTransport buildTransport(McpServerConfig cfg) {
        if ("SSE".equalsIgnoreCase(cfg.getTransportType())) {
            return new McpSseTransport(cfg.getEndpoint(), objectMapper);
        } else if ("STDIO".equalsIgnoreCase(cfg.getTransportType())) {
            return new McpStdioTransport(cfg.getEndpoint(), objectMapper);
        }
        throw new McpException("Unsupported transport type: " + cfg.getTransportType());
    }

    @PreDestroy
    void shutdown() {
        activeTransports.forEach((id, transport) -> {
            try { transport.close(); } catch (Exception ignored) {}
        });
        activeTransports.clear();
    }
}
