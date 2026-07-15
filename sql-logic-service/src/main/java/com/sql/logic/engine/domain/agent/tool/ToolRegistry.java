package com.sql.logic.engine.domain.agent.tool;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central registry of all available tools (built-in + MCP-connected).
 * <p>
 * We registers the four built-in tools at startup. And we will add
 * MCP-connected tools dynamically via {@link #register(ToolDefinition)} and
 * {@link #unregister(String)}.
 * <p>
 * This registry is the canonical source of truth for tool metadata.
 * {@code AgentToolGate} uses {@link #isRegistered(String)} to validate
 * tool keys at runtime.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    @PostConstruct
    void registerBuiltins() {
        register(new ToolDefinition("sql",    "SQL Executor",
                "Generate and execute SQL queries against connected databases",
                ToolType.BUILTIN, null));
        register(new ToolDefinition("schema", "Schema Viewer",
                "Browse database schemas, table structures, columns and foreign keys",
                ToolType.BUILTIN, null));
        register(new ToolDefinition("python", "Python Analyzer",
                "Execute Python code in a sandboxed Docker container for data analysis and charting",
                ToolType.BUILTIN, null));
        register(new ToolDefinition("sample", "Data Sampler",
                "Fetch representative sample rows from database columns for better context",
                ToolType.BUILTIN, null));
        log.info("[ToolRegistry] Registered {} built-in tools: {}", tools.size(), tools.keySet());
    }

    /** Register a tool definition. Overwrites existing entry with the same name. */
    public void register(ToolDefinition def) {
        tools.put(def.name(), def);
        log.info("[ToolRegistry] Registered tool: {} (type={})", def.name(), def.type());
    }

    /** Remove a tool from the registry (for MCP disconnect / cleanup). */
    public void unregister(String name) {
        if (tools.remove(name) != null) {
            log.info("[ToolRegistry] Unregistered tool: {}", name);
        }
    }

    /** Return all currently registered tool definitions. */
    public List<ToolDefinition> listTools() {
        return new ArrayList<>(tools.values());
    }

    /** Return a specific tool definition, or null if not registered. */
    public ToolDefinition get(String name) {
        return tools.get(name);
    }

    /** True if a tool with the given name is currently registered. */
    public boolean isRegistered(String name) {
        return tools.containsKey(name);
    }
}
