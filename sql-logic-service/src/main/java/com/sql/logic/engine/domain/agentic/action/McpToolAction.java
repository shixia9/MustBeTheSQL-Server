package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agentic.core.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executes a call to a registered MCP (Model Context Protocol) tool.
 */
public class McpToolAction implements AgentAction {

    @Override
    public String name() { return "mcp_tool"; }

    @Override
    public String description() { return "调用 MCP 工具"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String toolName = (String) context.context().getOrDefault("mcpToolName", "");
                if (toolName.isBlank()) {
                    return ActionOutput.fail("No MCP tool specified");
                }

                // In Phase 2, MCP execution delegates to the existing McpServerManager
                // via ToolAssistantAgent. For now, return a stub indicating the tool was invoked.
                return ActionOutput.success("MCP tool invoked: " + toolName,
                        Map.of("toolName", toolName, "status", "invoked"));
            } catch (Exception e) {
                return ActionOutput.fail("MCP tool execution failed: " + e.getMessage(), true);
            }
        });
    }
}
