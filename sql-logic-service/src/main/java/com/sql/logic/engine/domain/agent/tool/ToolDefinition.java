package com.sql.logic.engine.domain.agent.tool;

/**
 * Immutable definition of a tool registered in the system.
 * <p>
 * Each tool has a unique {@code name} that serves as the key for
 * Agent Studio tool switches ({@code AGENT_TOOLS} in state) and
 * for the ToolRegistry lookup.
 * <p>
 * Provenance fields ({@code serverId}, {@code userId}, {@code source}) enable
 * user-level isolation and server-scoped cleanup:
 * <ul>
 *   <li>{@code serverId} — the MCP server config id that owns this tool (null for BUILTIN/SKILL).</li>
 *   <li>{@code userId}   — the user who owns this tool; {@code null} means public (shared across users).</li>
 *   <li>{@code source}   — origin category (BUILTIN / MCP / SKILL).</li>
 * </ul>
 *
 * @param name             unique tool key (e.g. "sql", "schema", "python", "sample")
 * @param displayName      human-readable label for UI
 * @param description      one-line explanation of what the tool does
 * @param type             BUILTIN / MCP_SSE / MCP_STDIO / DOCKER_PYTHON
 * @param parametersSchema JSON Schema string describing the tool's input parameters (nullable for BUILTIN tools)
 * @param serverId         MCP server config id that owns this tool (nullable for BUILTIN/SKILL)
 * @param userId           owning user id; {@code null} means public/shared (nullable)
 * @param source           origin of the tool (BUILTIN / MCP / SKILL)
 */
public record ToolDefinition(
        String name,
        String displayName,
        String description,
        ToolType type,
        String parametersSchema,
        Long serverId,
        Long userId,
        ToolSource source
) {
    public ToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Tool name must not be blank");
        if (type == null) throw new IllegalArgumentException("Tool type must not be null");
        if (source == null) throw new IllegalArgumentException("Tool source must not be null");
    }
}
