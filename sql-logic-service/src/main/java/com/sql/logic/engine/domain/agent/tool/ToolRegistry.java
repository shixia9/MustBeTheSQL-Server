package com.sql.logic.engine.domain.agent.tool;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry of all available tools (built-in + MCP-connected + skill-backed).
 * <p>
 * Tools are partitioned by owner scope to support user-level isolation while keeping
 * a shared public scope for built-ins and any tool registered with {@code userId=null}.
 * Lookups by a user consider both that user's private scope and the public scope,
 * with the user's private scope taking precedence.
 * <p>
 * Storage is a {@link ConcurrentHashMap} of {@code scopeKey -> (toolName -> ToolDefinition)},
 * where {@code scopeKey} is the userId (or {@link #PUBLIC_SCOPE} for public tools).
 * All operations are thread-safe via {@code computeIfAbsent} / atomic {@code remove}.
 * <p>
 * This registry is the canonical source of truth for tool metadata.
 * {@code AgentToolGate} uses {@link #isRegistered(String)} to validate
 * tool keys at runtime. The legacy userId-less overloads delegate to the public scope
 * so existing single-agent graph nodes keep working unchanged.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** Scope key used for public/shared tools (registered with {@code userId=null}). */
    private static final Long PUBLIC_SCOPE = 0L;

    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, ToolDefinition>> toolsByScope = new ConcurrentHashMap<>();

    @PostConstruct
    void registerBuiltins() {
        register(new ToolDefinition("sql",    "SQL Executor",
                "Generate and execute SQL queries against connected databases",
                ToolType.BUILTIN, null, null, null, ToolSource.BUILTIN));
        register(new ToolDefinition("schema", "Schema Viewer",
                "Browse database schemas, table structures, columns and foreign keys",
                ToolType.BUILTIN, null, null, null, ToolSource.BUILTIN));
        register(new ToolDefinition("python", "Python Analyzer",
                "Execute Python code in a sandboxed Docker container for data analysis and charting",
                ToolType.BUILTIN, null, null, null, ToolSource.BUILTIN));
        register(new ToolDefinition("sample", "Data Sampler",
                "Fetch representative sample rows from database columns for better context",
                ToolType.BUILTIN, null, null, null, ToolSource.BUILTIN));
        log.info("[ToolRegistry] Registered {} built-in tools: {}",
                toolsByScope.getOrDefault(PUBLIC_SCOPE, new ConcurrentHashMap<>()).size(),
                toolsByScope.getOrDefault(PUBLIC_SCOPE, new ConcurrentHashMap<>()).keySet());
    }

    // ------------------------------------------------------------------
    // New user-scoped API
    // ------------------------------------------------------------------

    /**
     * Register a tool under a user scope. {@code userId=null} means public/shared.
     * Overwrites any existing entry with the same name in that scope.
     */
    public void register(Long userId, ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool definition must not be null");
        scope(userId).put(tool.name(), tool);
        log.info("[ToolRegistry] Registered tool: {} (type={}, source={}, scope={})",
                tool.name(), tool.type(), tool.source(), scopeLabel(userId));
    }

    /** Remove a tool from a user scope (or public scope if {@code userId=null}). */
    public void unregister(Long userId, String name) {
        ConcurrentHashMap<String, ToolDefinition> scope = scope(userId);
        if (scope.remove(name) != null) {
            log.info("[ToolRegistry] Unregistered tool: {} (scope={})", name, scopeLabel(userId));
        }
    }

    /**
     * True if a tool with the given name exists in the user's private scope OR
     * the public scope. {@code userId=null} only checks the public scope.
     */
    public boolean isRegistered(Long userId, String name) {
        if (userId != null) {
            ConcurrentHashMap<String, ToolDefinition> userScope = toolsByScope.get(userId);
            if (userScope != null && userScope.containsKey(name)) return true;
        }
        ConcurrentHashMap<String, ToolDefinition> pub = toolsByScope.get(PUBLIC_SCOPE);
        return pub != null && pub.containsKey(name);
    }

    /**
     * Get a tool by name from the user's private scope, falling back to the public scope.
     * Returns {@code null} if not found in either. {@code userId=null} only queries public scope.
     */
    public ToolDefinition getTool(Long userId, String name) {
        if (userId != null) {
            ConcurrentHashMap<String, ToolDefinition> userScope = toolsByScope.get(userId);
            if (userScope != null) {
                ToolDefinition def = userScope.get(name);
                if (def != null) return def;
            }
        }
        ConcurrentHashMap<String, ToolDefinition> pub = toolsByScope.get(PUBLIC_SCOPE);
        return pub == null ? null : pub.get(name);
    }

    /**
     * List all tools visible to a user: the user's own private tools plus all public tools.
     * Public tools that share a name with a user-scoped tool are shadowed by the user's version.
     */
    public List<ToolDefinition> listTools(Long userId) {
        ConcurrentHashMap<String, ToolDefinition> pub = toolsByScope.getOrDefault(PUBLIC_SCOPE, new ConcurrentHashMap<>());
        java.util.Map<String, ToolDefinition> merged = new java.util.LinkedHashMap<>(pub);
        if (userId != null) {
            ConcurrentHashMap<String, ToolDefinition> userScope = toolsByScope.get(userId);
            if (userScope != null) merged.putAll(userScope);
        }
        return new ArrayList<>(merged.values());
    }

    /** List only the tools owned by a specific user (excludes public tools). */
    public List<ToolDefinition> listByUser(Long userId) {
        if (userId == null) return List.of();
        ConcurrentHashMap<String, ToolDefinition> userScope = toolsByScope.get(userId);
        return userScope == null ? List.of() : new ArrayList<>(userScope.values());
    }

    /** List all tools across all scopes whose source matches the given value. */
    public List<ToolDefinition> listBySource(ToolSource source) {
        Objects.requireNonNull(source, "source must not be null");
        List<ToolDefinition> out = new ArrayList<>();
        for (ConcurrentHashMap<String, ToolDefinition> scope : toolsByScope.values()) {
            for (ToolDefinition def : scope.values()) {
                if (source.equals(def.source())) out.add(def);
            }
        }
        return out;
    }

    /**
     * Remove every tool owned by the given MCP server, across all user scopes.
     * Used when an MCP server is disconnected to clean up its discovered tools.
     */
    public int unregisterByServer(Long serverId) {
        if (serverId == null) return 0;
        int removed = 0;
        for (ConcurrentHashMap<String, ToolDefinition> scope : toolsByScope.values()) {
            for (ToolDefinition def : new ArrayList<>(scope.values())) {
                if (serverId.equals(def.serverId()) && scope.remove(def.name(), def)) {
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("[ToolRegistry] Unregistered {} tools for serverId={}", removed, serverId);
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // Backward-compatible API (delegates to public scope, userId=null)
    // ------------------------------------------------------------------

    /** Legacy: register into the public scope. */
    public void register(ToolDefinition def) {
        register(null, def);
    }

    /** Legacy: unregister from the public scope. */
    public void unregister(String name) {
        unregister(null, name);
    }

    /** Legacy: list all public tools. */
    public List<ToolDefinition> listTools() {
        ConcurrentHashMap<String, ToolDefinition> pub = toolsByScope.getOrDefault(PUBLIC_SCOPE, new ConcurrentHashMap<>());
        return new ArrayList<>(pub.values());
    }

    /** Legacy: lookup in the public scope only. */
    public ToolDefinition get(String name) {
        return getTool(null, name);
    }

    /** Legacy alias for {@link #get(String)}. */
    public ToolDefinition getTool(String name) {
        return getTool(null, name);
    }

    /** Legacy: true if a tool with the given name is registered in the public scope. */
    public boolean isRegistered(String name) {
        return isRegistered(null, name);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Get (or lazily create) the scope map for a given userId (null → public scope). */
    private ConcurrentHashMap<String, ToolDefinition> scope(Long userId) {
        return toolsByScope.computeIfAbsent(userId == null ? PUBLIC_SCOPE : userId, k -> new ConcurrentHashMap<>());
    }

    private static String scopeLabel(Long userId) {
        return userId == null ? "public" : ("user=" + userId);
    }
}
