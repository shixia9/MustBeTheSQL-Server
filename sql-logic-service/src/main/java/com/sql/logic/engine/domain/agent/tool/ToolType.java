package com.sql.logic.engine.domain.agent.tool;

/**
 * Type of tool registered in the system.
 * <p>
 * BUILTIN — native SQL-Logic-Engine tools (SQL execution, schema browsing, etc.)
 * MCP_SSE / MCP_STDIO — external tools connected via MCP protocol (Phase D2)
 * DOCKER_PYTHON — Python sandbox execution via Docker
 */
public enum ToolType {
    BUILTIN,
    MCP_SSE,
    MCP_STDIO,
    DOCKER_PYTHON
}
