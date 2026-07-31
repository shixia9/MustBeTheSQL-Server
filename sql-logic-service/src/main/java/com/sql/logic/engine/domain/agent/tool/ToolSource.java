package com.sql.logic.engine.domain.agent.tool;

/**
 * Origin of a registered tool, used for provenance tracking and routing.
 * <p>
 * BUILTIN — native SQL-Logic-Engine tools registered at startup (sql/schema/python/sample)
 * MCP     — external tools discovered via the MCP protocol (owned by a server, scoped to a user)
 * SKILL   — tools backed by the lightweight Skill system (Phase 3 of the multi-agent refactor)
 */
public enum ToolSource {
    BUILTIN,
    MCP,
    SKILL
}
