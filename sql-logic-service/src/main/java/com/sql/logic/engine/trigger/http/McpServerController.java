package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.domain.agent.tool.mcp.McpServerManager;
import com.sql.logic.engine.infrastructure.po.McpServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/mcp-servers")
public class McpServerController {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);

    private final McpServerManager mcpServerManager;

    public McpServerController(McpServerManager mcpServerManager) {
        this.mcpServerManager = mcpServerManager;
    }

    @GetMapping
    public Result<List<McpServerConfig>> list() {
        Long userId = getCurrentUserId();
        return Result.success(mcpServerManager.listServers(userId));
    }

    @PostMapping
    public Result<Map<String, Object>> add(@RequestBody Map<String, Object> body) {
        Long userId = getCurrentUserId();
        String name = (String) body.get("name");
        String transportType = (String) body.get("transportType");
        String endpoint = (String) body.get("endpoint");
        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) body.get("env");
        if (name == null || transportType == null || endpoint == null) {
            return Result.error(400, "name, transportType, and endpoint are required");
        }
        try {
            McpServerConfig cfg = mcpServerManager.addServer(userId, name, transportType.toUpperCase(), endpoint, env);
            return Result.success(Map.of("id", cfg.getId(), "connected",
                    mcpServerManager.isConnected(cfg.getId())));
        } catch (Exception e) {
            log.warn("[McpServerController] Failed to add MCP server: {}", e.getMessage());
            return Result.error(500, "Failed to add MCP server: " + e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String transportType = (String) body.get("transportType");
        String endpoint = (String) body.get("endpoint");
        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) body.get("env");
        if (name == null || transportType == null || endpoint == null) {
            return Result.error(400, "name, transportType, and endpoint are required");
        }
        try {
            McpServerConfig cfg = mcpServerManager.updateServer(id, name, transportType.toUpperCase(), endpoint, env);
            return Result.success(Map.of("id", cfg.getId(), "connected",
                    mcpServerManager.isConnected(cfg.getId())));
        } catch (Exception e) {
            log.warn("[McpServerController] Failed to update MCP server: {}", e.getMessage());
            return Result.error(500, "Failed to update MCP server: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        mcpServerManager.removeServer(id);
        return Result.success(null);
    }

    @PostMapping("/{id}/connect")
    public Result<Map<String, Object>> connect(@PathVariable Long id) {
        try {
            mcpServerManager.connectAndRegister(id);
            return Result.success(Map.of("connected", true));
        } catch (Exception e) {
            return Result.error(500, "Connection failed: " + e.getMessage());
        }
    }

    @PostMapping("/{id}/disconnect")
    public Result<Map<String, Object>> disconnect(@PathVariable Long id) {
        mcpServerManager.disconnect(id);
        return Result.success(Map.of("connected", false));
    }

    @GetMapping("/{id}/status")
    public Result<Map<String, Object>> status(@PathVariable Long id) {
        return Result.success(Map.of("connected", mcpServerManager.isConnected(id)));
    }

    private Long getCurrentUserId() {
        return Long.valueOf((String) StpUtil.getLoginId());
    }
}
