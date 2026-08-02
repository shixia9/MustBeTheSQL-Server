package com.sql.logic.engine.domain.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for {@link ToolInvocationGuard}.
 * <p>
 * Covers: public-tool access, user-private-tool access, denial for
 * non-owner, null/blank toolName handling, anonymous (null userId) access.
 */
class ToolInvocationGuardTest {

    private ToolRegistry registry;
    private ToolInvocationGuard guard;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.registerBuiltins(); // 4 builtins in public scope

        // Register an MCP tool owned by user 100
        registry.register(100L, new ToolDefinition(
                "user_private_tool", "Private Tool", "User 100 private tool",
                ToolType.MCP_STDIO, "{}", 1L, 100L, ToolSource.MCP));

        guard = new ToolInvocationGuard(registry);
    }

    // ==================== Public tool access ====================

    @Test
    void shouldAllowAccessToPublicBuiltinForAnyUser() {
        assertDoesNotThrow(() -> guard.check(100L, "sql"));
        assertDoesNotThrow(() -> guard.check(200L, "sql"));
    }

    @Test
    void shouldAllowAccessToPublicBuiltinForAnonymous() {
        assertDoesNotThrow(() -> guard.check(null, "sql"));
    }

    // ==================== User-private tool access ====================

    @Test
    void shouldAllowOwnerAccessToPrivateTool() {
        assertDoesNotThrow(() -> guard.check(100L, "user_private_tool"));
    }

    @Test
    void shouldDenyNonOwnerAccessToPrivateTool() {
        ToolPermissionException ex = assertThrows(ToolPermissionException.class,
                () -> guard.check(200L, "user_private_tool"));
        assertTrue(ex.getMessage().contains("not accessible"));
    }

    @Test
    void shouldDenyAnonymousAccessToPrivateTool() {
        ToolPermissionException ex = assertThrows(ToolPermissionException.class,
                () -> guard.check(null, "user_private_tool"));
        assertTrue(ex.getMessage().contains("not accessible"));
    }

    // ==================== Nonexistent tool ====================

    @Test
    void shouldDenyAccessToNonexistentTool() {
        ToolPermissionException ex = assertThrows(ToolPermissionException.class,
                () -> guard.check(100L, "nonexistent_tool"));
        assertTrue(ex.getMessage().contains("not accessible"));
    }

    // ==================== Null/blank toolName ====================

    @Test
    void shouldDenyAccessForNullToolName() {
        ToolPermissionException ex = assertThrows(ToolPermissionException.class,
                () -> guard.check(100L, null));
        assertTrue(ex.getMessage().contains("no tool specified"));
    }

    @Test
    void shouldDenyAccessForBlankToolName() {
        ToolPermissionException ex = assertThrows(ToolPermissionException.class,
                () -> guard.check(100L, "   "));
        assertTrue(ex.getMessage().contains("no tool specified"));
    }

    @Test
    void shouldDenyAccessForEmptyToolName() {
        ToolPermissionException ex = assertThrows(ToolPermissionException.class,
                () -> guard.check(100L, ""));
        assertTrue(ex.getMessage().contains("no tool specified"));
    }
}
