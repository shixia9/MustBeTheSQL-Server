package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Task decomposition Agent — generates structured PlanStep lists from user input.
 */
public class PlannerAgent extends ConversableAgent {

    public static final ProfileConfig DEFAULT_PROFILE = ProfileConfig.builder()
            .name("Planner")
            .role("任务规划师")
            .goal("将用户输入分解为结构化的子任务列表，并分配给最合适的 Agent")
            .constraints(List.of(
                    "同一类型的无依赖子任务可以并行安排",
                    "不同 Agent 可以并行工作",
                    "尽量复用已有的 Agent 类型（DataScientist, CodeAssistant, ToolAssistant）",
                    "遵守依赖关系声明",
                    "直接生成最终计划，不要多余解释"
            ))
            .description("专业的数据分析任务规划师，擅长将复杂分析需求拆解为可执行的步骤")
            .systemPromptTemplate("""
                    你是 {name}，{description}。
                    角色：{role}
                    目标：{goal}

                    约束条件：
                    {constraints}

                    可用的 Agent 及其能力：
                    {agents}

                    请根据用户输入，生成 JSON 格式的执行计划。
                    输出格式必须为 JSON 数组，每个元素包含 serial_number, agent, content, rely 字段：
                    [{"serial_number": 1, "agent": "DataScientist", "content": "任务描述", "rely": ""}]

                    rely 字段填写依赖的步骤编号（逗号分隔），无依赖则为空字符串。
                    重要：即使信息有限，也必须至少生成一个步骤。如果用户想分析数据，
                    至少生成一个 DataScientist 步骤来查询数据库。
                    """)
            .build();

    public PlannerAgent() {
        this.profile = DEFAULT_PROFILE;
    }

    @Override
    public CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender) {
        if (actions.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionOutput.fail("No PlanAction registered on PlannerAgent"));
        }
        // PlanAction is always the first action
        return actions.get(0).execute(message, this);
    }

    @Override
    protected String buildSystemPrompt(String observation, String memoryContext,
                                        String resourceContext, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderProfilePrompt());
        sb.append("\n");

        // Inject available agent descriptions
        @SuppressWarnings("unchecked")
        List<String> agentDescriptions = (List<String>) context.get("agentDescriptions");
        if (agentDescriptions != null && !agentDescriptions.isEmpty()) {
            sb.append("可用的 Agent 及其能力:\n");
            for (String desc : agentDescriptions) {
                sb.append("- ").append(desc).append("\n");
            }
        }

        if (resourceContext != null && !resourceContext.isBlank()) {
            sb.append("\n### 数据资源\n").append(resourceContext);
        }

        return sb.toString();
    }

    @Override
    protected String buildUserPrompt(String observation, String memoryContext,
                                      String resourceContext, Map<String, Object> context) {
        return "请为以下用户需求生成执行计划:\n" + observation;
    }
}
