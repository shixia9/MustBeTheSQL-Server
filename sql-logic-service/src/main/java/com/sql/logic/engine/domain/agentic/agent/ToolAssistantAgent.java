package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tool Assistant Agent — executes and fixes MCP (Model Context Protocol) tool calls.
 * Consolidates MCP_TOOL_EXECUTOR + MCP_TOOL_FIXER nodes into a single Agent.
 */
public class ToolAssistantAgent extends ConversableAgent {

    public static final ProfileConfig DEFAULT_PROFILE = ProfileConfig.builder()
            .name("ToolAssistant")
            .role("工具调用专家")
            .goal("根据任务需求调用 MCP 外部工具并处理返回结果")
            .constraints(List.of(
                    "仅调用已注册的 MCP 工具",
                    "工具调用失败时尝试修复参数后重试",
                    "返回结果需格式化后传递给下游 Agent"
            ))
            .description("MCP 工具调用专家，擅长与外部系统交互")
            .build();

    public ToolAssistantAgent() {
        this.profile = DEFAULT_PROFILE;
    }

    @Override
    public CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender) {
        if (actions.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionOutput.fail("No actions registered on ToolAssistantAgent"));
        }

        ActionOutput previousReport = message.actionReport();
        if (previousReport != null && !previousReport.isExeSuccess() && previousReport.hasRetry()) {
            for (AgentAction action : actions) {
                if ("mcp_tool_fix".equals(action.name())) {
                    return action.execute(message, this);
                }
            }
        }
        return actions.get(0).execute(message, this);
    }

    @Override
    protected String buildSystemPrompt(String observation, String memoryContext,
                                        String resourceContext, Map<String, Object> context) {
        return renderProfilePrompt();
    }

    @Override
    protected String buildUserPrompt(String observation, String memoryContext,
                                      String resourceContext, Map<String, Object> context) {
        return observation;
    }
}
