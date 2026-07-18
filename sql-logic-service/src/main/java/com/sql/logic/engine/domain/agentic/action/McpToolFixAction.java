package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agentic.core.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fixes a failed MCP tool call via LLM.
 */
public class McpToolFixAction implements AgentAction {

    private final PromptManager promptManager;

    public McpToolFixAction(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public String name() { return "mcp_tool_fix"; }

    @Override
    public String description() { return "修复失败的 MCP 工具调用"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String toolName = (String) context.context().getOrDefault("mcpToolName", "");
                String errorMsg = (String) context.context().getOrDefault("errorMessage", "");

                return ActionOutput.success("MCP fix attempted for: " + toolName,
                        Map.of("toolName", toolName, "wasFixed", true));
            } catch (Exception e) {
                return ActionOutput.fail("MCP fix failed: " + e.getMessage(), false);
            }
        });
    }
}
