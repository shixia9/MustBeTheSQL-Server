package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Code Assistant Agent — generates, executes, and analyzes Python code
 * for data analysis tasks. Consolidates what were three separate StateGraph nodes
 * (PYTHON_GENERATION, PYTHON_EXECUTION, PYTHON_ANALYSIS) into a single Agent
 * with an internal generate→execute→analyze retry loop.
 */
public class CodeAssistantAgent extends ConversableAgent {

    public static final ProfileConfig DEFAULT_PROFILE = ProfileConfig.builder()
            .name("CodeAssistant")
            .role("代码工程师")
            .goal("根据数据分析需求生成、执行并验证 Python 代码")
            .constraints(List.of(
                    "仅生成数据分析相关的 Python 代码",
                    "代码必须在 Docker 沙箱中安全执行",
                    "使用 pandas/matplotlib 等标准数据分析库",
                    "执行结果需经过正确性验证"
            ))
            .description("精通 Python 数据分析的代码工程师，擅长数据处理和统计建模")
            .systemPromptTemplate("""
                    你是 {name}，{description}。
                    角色：{role}
                    目标：{goal}

                    约束条件：
                    {constraints}

                    请生成可执行的 Python 代码来完成数据分析任务。
                    代码应包含必要的 import 语句和结果输出。
                    """)
            .build();

    public CodeAssistantAgent() {
        this.profile = DEFAULT_PROFILE;
    }

    @Override
    public CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender) {
        if (actions.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionOutput.fail("No actions registered on CodeAssistantAgent"));
        }

        ActionOutput previousReport = message.actionReport();
        // If generation failed, try fix; if execution failed, retry; if analysis failed, re-generate
        if (previousReport != null && !previousReport.isExeSuccess() && previousReport.hasRetry()) {
            // Retry: go back to generation
            return actions.get(0).execute(message, this);
        }
        return actions.get(0).execute(message, this);
    }

    @Override
    public CompletableFuture<VerifyResult> verify(AgentMessage message, Agent sender) {
        ActionOutput ao = message.actionReport();
        if (ao == null || !ao.isExeSuccess()) {
            return CompletableFuture.completedFuture(
                    VerifyResult.fail(ao != null ? ao.content() : "No action output"));
        }
        // Additional check: ensure code output is not empty
        if (ao.content() == null || ao.content().isBlank()) {
            return CompletableFuture.completedFuture(VerifyResult.fail("Python 代码输出为空"));
        }
        return CompletableFuture.completedFuture(VerifyResult.PASSED);
    }

    @Override
    protected String buildSystemPrompt(String observation, String memoryContext,
                                        String resourceContext, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderProfilePrompt());
        sb.append("\n");
        if (resourceContext != null && !resourceContext.isBlank()) {
            sb.append("### 数据上下文\n").append(resourceContext).append("\n");
        }
        return sb.toString();
    }

    @Override
    protected String buildUserPrompt(String observation, String memoryContext,
                                      String resourceContext, Map<String, Object> context) {
        return "请为以下数据分析任务生成 Python 代码:\n" + observation;
    }
}
