package com.sql.logic.engine.domain.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for {@link ToolRegistry} — multi-tenant scope partitioning.
 * <p>
 * Covers: user-scoped registration, public scope, user-private shadowing
 * of public tools, backward-compatible legacy API, unregisterByServer,
 * and listBySource.
 */
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        // Register builtins (normally via @PostConstruct, called manually in tests)
        registry.registerBuiltins();
    }

    // ==================== Builtins (public scope) ====================

    @Test
    void shouldRegisterFourBuiltinToolsInPublicScope() {
        List<ToolDefinition> tools = registry.listTools(); // legacy: public only
        assertEquals(4, tools.size());
    }

    @Test
    void shouldFindBuiltinByNameLegacyApi() {
        ToolDefinition def = registry.get("sql");
        assertNotNull(def);
        assertEquals("sql", def.name());
        assertEquals(ToolSource.BUILTIN, def.source());
    }

    @Test
    void shouldFindBuiltinByNameUserScopedApi() {
        ToolDefinition def = registry.getTool(100L, "sql");
        assertNotNull(def);
        assertEquals("sql", def.name());
    }

    @Test
    void shouldReturnNullForUnknownTool() {
        assertNull(registry.get("nonexistent"));
        assertNull(registry.getTool(100L, "nonexistent"));
    }

    // ==================== User-scoped registration ====================

    @Test
    void shouldRegisterToolInUserScope() {
        ToolDefinition mcpTool = mcpToolDef("my_mcp_tool", 42L, 100L);
        registry.register(100L, mcpTool);

        assertTrue(registry.isRegistered(100L, "my_mcp_tool"));
        // Not visible from null userId (public only)
        assertFalse(registry.isRegistered(null, "my_mcp_tool"));
        // Not visible from another user
        assertFalse(registry.isRegistered(200L, "my_mcp_tool"));
    }

    @Test
    void shouldListToolsVisibleToUserIncludingPublic() {
        // User 100 registers an MCP tool
        registry.register(100L, mcpToolDef("my_mcp_tool", 42L, 100L));

        List<ToolDefinition> visible = registry.listTools(100L);
        // 4 builtins (public) + 1 MCP (user 100) = 5
        assertEquals(5, visible.size());
    }

    @Test
    void shouldListOnlyPublicToolsForAnonymousUser() {
        registry.register(100L, mcpToolDef("my_mcp_tool", 42L, 100L));

        List<ToolDefinition> visible = registry.listTools(null);
        assertEquals(4, visible.size()); // only 4 builtins
    }

    @Test
    void shouldListOnlyPublicToolsForDifferentUser() {
        registry.register(100L, mcpToolDef("my_mcp_tool", 42L, 100L));

        List<ToolDefinition> visible = registry.listTools(200L);
        assertEquals(4, visible.size()); // only 4 builtins
    }

    // ==================== User tool shadows public tool ====================

    @Test
    void shouldShadowPublicToolWithUserPrivateVersion() {
        // Register a user-private "sql" that shadows the builtin
        ToolDefinition userSql = new ToolDefinition(
                "sql", "My Custom SQL", "Custom SQL tool",
                ToolType.MCP_STDIO, "{}", 99L, 100L, ToolSource.MCP);
        registry.register(100L, userSql);

        ToolDefinition def = registry.getTool(100L, "sql");
        assertEquals("My Custom SQL", def.displayName());
        assertEquals(ToolSource.MCP, def.source());
    }

    @Test
    void shouldNotShadowPublicToolForOtherUser() {
        registry.register(100L, new ToolDefinition(
                "sql", "My Custom SQL", "Custom SQL tool",
                ToolType.MCP_STDIO, "{}", 99L, 100L, ToolSource.MCP));

        // User 200 still gets the public builtin version
        ToolDefinition def = registry.getTool(200L, "sql");
        assertEquals("SQL Executor", def.displayName());
        assertEquals(ToolSource.BUILTIN, def.source());
    }

    // ==================== Unregister ====================

    @Test
    void shouldUnregisterFromUserScope() {
        registry.register(100L, mcpToolDef("temp_tool", 1L, 100L));
        assertTrue(registry.isRegistered(100L, "temp_tool"));

        registry.unregister(100L, "temp_tool");
        assertFalse(registry.isRegistered(100L, "temp_tool"));
    }

    @Test
    void unregisterShouldNotAffectPublicTools() {
        registry.unregister(100L, "sql"); // try to unregister from user scope
        // Public "sql" should still exist
        assertTrue(registry.isRegistered(100L, "sql"));
    }

    // ==================== unregisterByServer ====================

    @Test
    void shouldUnregisterAllToolsOwnedByServerAcrossScopes() {
        Long serverId = 42L;
        // Register tools for two users on the same server
        registry.register(100L, mcpToolDef("tool_a", serverId, 100L));
        registry.register(100L, mcpToolDef("tool_b", serverId, 100L));
        registry.register(200L, mcpToolDef("tool_a", serverId, 200L)); // same name, diff user
        registry.register(200L, mcpToolDef("tool_c", serverId, 200L));
        // Register a tool for a different server (should survive)
        registry.register(100L, mcpToolDef("tool_d", 99L, 100L));

        int removed = registry.unregisterByServer(serverId);
        assertEquals(4, removed);

        // tools for server 42 are gone
        assertFalse(registry.isRegistered(100L, "tool_a"));
        assertFalse(registry.isRegistered(100L, "tool_b"));
        assertFalse(registry.isRegistered(200L, "tool_a"));
        assertFalse(registry.isRegistered(200L, "tool_c"));

        // tool_d (different server) survives
        assertTrue(registry.isRegistered(100L, "tool_d"));
    }

    @Test
    void unregisterByServerShouldHandleNullServerId() {
        assertEquals(0, registry.unregisterByServer(null));
    }

    // ==================== listBySource ====================

    @Test
    void shouldListToolsBySource() {
        registry.register(100L, mcpToolDef("mcp1", 1L, 100L));
        registry.register(200L, mcpToolDef("mcp2", 2L, 200L));

        List<ToolDefinition> builtins = registry.listBySource(ToolSource.BUILTIN);
        assertEquals(4, builtins.size());

        List<ToolDefinition> mcps = registry.listBySource(ToolSource.MCP);
        assertEquals(2, mcps.size());
    }

    // ==================== listByUser ====================

    @Test
    void shouldListToolsOwnedBySpecificUser() {
        registry.register(100L, mcpToolDef("mcp1", 1L, 100L));
        registry.register(100L, mcpToolDef("mcp2", 2L, 100L));

        List<ToolDefinition> userTools = registry.listByUser(100L);
        assertEquals(2, userTools.size());
    }

    @Test
    void listByUserShouldExcludePublicTools() {
        List<ToolDefinition> userTools = registry.listByUser(100L);
        assertTrue(userTools.isEmpty()); // only public builtins exist
    }

    @Test
    void listByUserShouldReturnEmptyForNullUserId() {
        assertTrue(registry.listByUser(null).isEmpty());
    }

    // ==================== Backward-compatible API ====================

    @Test
    void legacyRegisterShouldGoToPublicScope() {
        ToolDefinition custom = new ToolDefinition(
                "custom", "Custom", "desc",
                ToolType.BUILTIN, null, null, null, ToolSource.BUILTIN);
        registry.register(custom); // legacy: public scope

        assertTrue(registry.isRegistered(null, "custom"));
        assertTrue(registry.isRegistered(100L, "custom"));
    }

    @Test
    void legacyIsRegisteredShouldCheckPublicScope() {
        assertTrue(registry.isRegistered("sql"));
        assertFalse(registry.isRegistered("nonexistent"));
    }

    @Test
    void legacyGetToolShouldCheckPublicScope() {
        assertNotNull(registry.getTool("sql"));
        assertNull(registry.getTool("nonexistent"));
    }

    @Test
    void legacyUnregisterShouldRemoveFromPublicScope() {
        // Can't unregister a builtin (they're in public scope), so test with custom
        registry.register(new ToolDefinition(
                "custom", "Custom", "desc", ToolType.BUILTIN,
                null, null, null, ToolSource.BUILTIN));

        registry.unregister("custom");
        assertFalse(registry.isRegistered("custom"));
    }

    // ==================== Edge cases ====================

    @Test
    void shouldOverwriteExistingToolInSameScope() {
        ToolDefinition v1 = mcpToolDef("dup", 1L, 100L);
        registry.register(100L, v1);
        assertEquals("MCP Tool: dup", registry.getTool(100L, "dup").displayName());

        ToolDefinition v2 = new ToolDefinition(
                "dup", "Updated Display", "updated desc",
                ToolType.MCP_STDIO, "{}", 1L, 100L, ToolSource.MCP);
        registry.register(100L, v2);
        assertEquals("Updated Display", registry.getTool(100L, "dup").displayName());
    }

    @Test
    void shouldBeThreadSafeForConcurrentRegistration() throws Exception {
        int threads = 10;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(() ->
                    registry.register((long) idx, mcpToolDef("thread_tool_" + idx, (long) idx, (long) idx))
            );
            ts[i].start();
        }
        for (Thread t : ts) t.join();

        for (int i = 0; i < threads; i++) {
            assertTrue(registry.isRegistered((long) i, "thread_tool_" + i));
        }
    }

    // ==================== Helpers ====================

    private static ToolDefinition mcpToolDef(String name, Long serverId, Long userId) {
        return new ToolDefinition(
                name, "MCP Tool: " + name, "Description for " + name,
                ToolType.MCP_STDIO, "{}", serverId, userId, ToolSource.MCP);
    }
}
