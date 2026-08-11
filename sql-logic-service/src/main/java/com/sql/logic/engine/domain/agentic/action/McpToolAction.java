package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.tool.ToolInvocationGuard;
import com.sql.logic.engine.domain.agent.tool.ToolPermissionException;
import com.sql.logic.engine.domain.agent.tool.mcp.McpException;
import com.sql.logic.engine.domain.agent.tool.mcp.McpServerManager;
import com.sql.logic.engine.domain.agentic.core.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executes a call to a registered MCP (Model Context Protocol) tool.
 * <p>
 * The action reads the target tool name and arguments from the message
 * context, verifies the caller's user scope via {@link ToolInvocationGuard},
 * and delegates the actual invocation to {@link McpServerManager#callTool},
 * which routes the request to the exact MCP server that owns the tool.
 */
public class McpToolAction implements AgentAction {

    public static final String NAME = "mcp_tool";

    private final McpServerManager mcpServerManager;
    private final ToolInvocationGuard guard;

    public McpToolAction(McpServerManager mcpServerManager, ToolInvocationGuard guard) {
        this.mcpServerManager = mcpServerManager;
        this.guard = guard;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "调用 MCP 工具";
    }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // When the user invoked a tool directly via the "/" command
                // palette, the toolInvocation context map is the source of truth
                // for toolName/args. Otherwise fall back to mcpToolName/mcpToolArgs
                // for backward compatibility with the Planner-driven flow.
                Map<String, Object> toolInvocation = readToolInvocation(context);

                String toolName;
                if (toolInvocation != null) {
                    toolName = readStringFromMap(toolInvocation, "toolName");
                } else {
                    toolName = (String) context.context().getOrDefault("mcpToolName", "");
                }
                if (toolName == null || toolName.isBlank()) {
                    return ActionOutput.fail("No MCP tool specified");
                }

                Long userId = readUserId(context);

                // Reuse fixed arguments produced by McpToolFixAction on a previous
                // retry, when present; otherwise fall back to the original args.
                Map<String, Object> args = readFixedArgs(context);
                if (args == null) {
                    if (toolInvocation != null) {
                        args = readInvocationArgs(toolInvocation);
                    } else {
                        args = readToolArgs(context);
                    }
                }

                // Enforce user-scope permission before dispatching.
                try {
                    guard.check(userId, toolName);
                } catch (ToolPermissionException e) {
                    return ActionOutput.fail(e.getMessage(), false);
                }

                String result = mcpServerManager.callTool(toolName, args, userId);
                return ActionOutput.success(result,
                        Map.of("toolName", toolName, "status", "executed"));
            } catch (McpException e) {
                return ActionOutput.fail(e.getMessage(), true);
            } catch (Exception e) {
                return ActionOutput.fail("MCP tool execution failed: " + e.getMessage(), true);
            }
        });
    }

    /**
     * Read the direct tool-invocation payload from context (T8.3). Returns null
     * when no direct invocation happened — the caller then falls back to the
     * legacy {@code mcpToolName}/{@code mcpToolArgs} context keys.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readToolInvocation(AgentMessage context) {
        Object v = context.context().get("toolInvocation");
        if (v instanceof Map<?, ?> m && !m.isEmpty()) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    /**
     * Read a string field from the toolInvocation payload (defensive against
     * non-string JSON values).
     */
    private String readStringFromMap(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    /**
     * Read the {@code args} sub-map from a direct toolInvocation payload.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readInvocationArgs(Map<String, Object> toolInvocation) {
        Object v = toolInvocation.get("args");
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /**
     * Read the owning user id from the message context. The value is propagated
     * by {@code ManagerAgent.forwardAllContext} and originally placed in state
     * by {@code AgentStateBridge.toAgentMessage} as a {@link Long}.
     */
    private Long readUserId(AgentMessage context) {
        Object v = context.context().get("userId");
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    /**
     * Read the original MCP tool arguments from context. Falls back to an empty
     * map when none were provided (the tool may take no arguments).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readToolArgs(AgentMessage context) {
        Object v = context.context().get("mcpToolArgs");
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    /**
     * If McpToolFixAction ran on a previous retry, its output carries the
     * corrected arguments under the {@code mcpToolArgs} data key so this action
     * can pick them up on the next attempt.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readFixedArgs(AgentMessage context) {
        ActionOutput prev = context.actionReport();
        if (prev == null || prev.data() == null) return null;
        Object v = prev.data().get("mcpToolArgs");
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }
}
