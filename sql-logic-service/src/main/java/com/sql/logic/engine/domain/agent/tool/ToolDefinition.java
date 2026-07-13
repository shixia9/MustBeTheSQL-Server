package com.sql.logic.engine.domain.agent.tool;

/**
 * Immutable definition of a tool registered in the system.
 * <p>
 * Each tool has a unique {@code name} that serves as the key for
 * Agent Studio tool switches ({@code AGENT_TOOLS} in state) and
 * for the ToolRegistry lookup.
 *
 * @param name             unique tool key (e.g. "sql", "schema", "python", "sample")
 * @param displayName      human-readable label for UI
 * @param description      one-line explanation of what the tool does
 * @param type             BUILTIN / MCP_SSE / MCP_STDIO / DOCKER_PYTHON
 * @param parametersSchema JSON Schema string describing the tool's input parameters (nullable for BUILTIN tools)
 */
public record ToolDefinition(
        String name,
        String displayName,
        String description,
        ToolType type,
        String parametersSchema
) {
    public ToolDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Tool name must not be blank");
        if (type == null) throw new IllegalArgumentException("Tool type must not be null");
    }
}
