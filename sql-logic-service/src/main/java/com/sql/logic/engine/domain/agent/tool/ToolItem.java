package com.sql.logic.engine.domain.agent.tool;

/**
 * Unified tool descriptor returned by the discovery API ({@code GET /api/v1/tools}).
 * <p>
 * Aggregates three kinds of invocable entities into a single flat list so the
 * frontend "/" command palette can render built-in tools, MCP-connected tools,
 * and DB-backed skills uniformly:
 * <ul>
 *   <li>{@code builtin} — native SQL-Logic-Engine tools (sql/schema/python/sample), invoked as a tool call.</li>
 *   <li>{@code mcp}     — external tools discovered via the MCP protocol, invoked as a tool call.</li>
 *   <li>{@code skill}   — packaged prompt templates, invoked by injecting their rendered prompt.</li>
 * </ul>
 * <p>
 * {@code invocationMode} tells the frontend how to dispatch a selection:
 * {@code call_tool} builds a tool-call request body, {@code inject_prompt} splices the
 * rendered prompt template into the input box. {@code source} mirrors {@link ToolSource}
 * names (BUILTIN/MCP/SKILL) for provenance tracking.
 *
 * @param kind             one of "builtin" / "mcp" / "skill"
 * @param name             unique key (tool name for builtin/mcp, skill name for skill)
 * @param displayName      human-readable label for UI
 * @param description      one-line explanation of what the item does
 * @param parametersSchema JSON Schema string for builtin/mcp tools; {@code null} for skills
 * @param invocationMode   one of "call_tool" / "inject_prompt"
 * @param source           provenance label: "BUILTIN" / "MCP" / "SKILL"
 */
public record ToolItem(
        String kind,
        String name,
        String displayName,
        String description,
        String parametersSchema,
        String invocationMode,
        String source
) {
}
