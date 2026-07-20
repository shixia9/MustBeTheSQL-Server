package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanStep;
import com.sql.logic.engine.domain.agentic.plan.PlanStatus;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;
import com.sql.logic.engine.domain.agentic.team.TeamMixin;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestration Manager Agent — the core scheduler that coordinates
 * PlannerAgent and worker Agents in a Plan → Dispatch → Execute → Report cycle.
 * Directly adapted from DB-GPT's {@code AutoPlanChatManager}.
 */
public class ManagerAgent extends ConversableAgent implements TeamMixin {

    public static final ProfileConfig DEFAULT_PROFILE = ProfileConfig.builder()
            .name("Manager")
            .role("编排管理者")
            .goal("根据 PlannerAgent 生成的计划调度合适的 Agent 执行子任务，汇总结果生成报告")
            .constraints(List.of(
                    "严格按照计划步骤顺序执行",
                    "有依赖关系的步骤必须等待前置步骤完成",
                    "步骤失败时根据重试次数决定是否继续",
                    "所有步骤完成后调用 DashboardAssistantAgent 生成最终报告"
            ))
            .description("数据分析任务的总调度，负责协调多个专业 Agent 完成复杂分析")
            .build();

    private final List<Agent> agents = new ArrayList<>();
    private PlanMemory planMemory;
    private PlannerAgent plannerAgent;
    private DashboardAssistantAgent dashboardAgent;
    private int maxRound = 100;

    // HITL support
    private boolean hitlEnabled = false;
    private String pendingThreadId;
    private CompletableFuture<Boolean> hitlFuture;

    public ManagerAgent() {
        this.profile = DEFAULT_PROFILE;
    }

    public void setPlanMemory(PlanMemory planMemory) { this.planMemory = planMemory; }
    public void setPlannerAgent(PlannerAgent plannerAgent) { this.plannerAgent = plannerAgent; }
    public void setDashboardAgent(DashboardAssistantAgent dashboardAgent) { this.dashboardAgent = dashboardAgent; }
    public void setHitlEnabled(boolean hitlEnabled) { this.hitlEnabled = hitlEnabled; }

    @Override
    public List<Agent> getAgents() { return agents; }

    @Override
    public PlanMemory getPlanMemory() { return planMemory; }

    // ========================================================================
    //  act() = orchestration main loop
    // ========================================================================

    @Override
    public CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender) {
        return CompletableFuture.supplyAsync(() -> {
            String threadId = (String) message.context().getOrDefault("threadId",
                    UUID.randomUUID().toString());
            String userInput = message.content();
            List<Map<String, String>> allStepResults = new ArrayList<>();

            for (int round = 0; round < maxRound; round++) {
                List<PlanStep> todoPlans = planMemory.getTodoPlans(threadId);

                // No plans → invoke PlannerAgent
                if (todoPlans.isEmpty()) {
                    if (round > 3) {
                        return ActionOutput.fail("重试 3 次仍无法生成有效计划");
                    }
                    AgentMessage planInput = AgentMessage.builder()
                            .content(userInput)
                            .currentGoal("生成执行计划")
                            .putContext("threadId", threadId)
                            .putContext("agentDescriptions", buildAgentDescriptions())
                            .rounds(message.rounds() + 1)
                            .build();
                    AgentMessage planResult = plannerAgent.generateReply(
                            planInput, this, null, null).join();

                    if (!planResult.success()) {
                        return ActionOutput.fail("PlannerAgent 计划生成失败: " + planResult.content());
                    }
                    continue;
                }

                // Take the first TODO plan step
                PlanStep currentPlan = todoPlans.get(0);
                currentPlan.setStatus(PlanStatus.RUNNING);

                // HITL gate
                if (hitlEnabled && needsHumanReview(currentPlan)) {
                    boolean approved = awaitHumanDecision(threadId, currentPlan);
                    if (!approved) {
                        return ActionOutput.fail("用户拒绝了计划步骤: " + currentPlan.getContent());
                    }
                }

                // Select speaker
                Agent speaker = selectSpeaker(currentPlan);
                if (speaker == null) {
                    return ActionOutput.fail("未找到可执行步骤的 Agent: " + currentPlan.getAgent());
                }

                // Build rely messages from dependency steps
                List<AgentMessage> relyMessages = processRelyMessages(threadId, currentPlan);

                // Build goal message
                AgentMessage goalMessage = AgentMessage.builder()
                        .content(currentPlan.getContent())
                        .currentGoal(currentPlan.getContent())
                        .putContext("threadId", threadId)
                        .putContext("plan_task_num", currentPlan.getSerialNumber())
                        .putContext("userId", message.context().get("userId"))
                        .putContext("connectionId", message.context().get("connectionId"))
                        .putContext("llmConfigId", message.context().get("llmConfigId"))
                        .rounds(message.rounds() + 1)
                        .build();

                // Send to speaker and get reply
                try {
                    send(goalMessage, speaker).join();
                    AgentMessage reply = speaker.generateReply(
                            goalMessage, this, relyMessages, null).join();

                    if (reply.success()) {
                        String result = reply.actionReport() != null
                                ? reply.actionReport().content() : reply.content();
                        planMemory.completeTask(threadId, currentPlan.getSerialNumber(), result);

                        allStepResults.add(Map.of(
                                "content", currentPlan.getContent(),
                                "agent", currentPlan.getAgent(),
                                "result", result
                        ));
                    } else {
                        if (currentPlan.getRetryTimes() < currentPlan.getMaxRetryTimes()) {
                            planMemory.updateTask(threadId, currentPlan.getSerialNumber(),
                                    PlanStatus.TODO, currentPlan.getRetryTimes() + 1,
                                    speaker.name(), reply.content());
                            continue;
                        } else {
                            planMemory.updateTask(threadId, currentPlan.getSerialNumber(),
                                    PlanStatus.FAILED, currentPlan.getRetryTimes() + 1,
                                    speaker.name(), reply.content());
                            return ActionOutput.fail(reply.content());
                        }
                    }
                } catch (Exception e) {
                    return ActionOutput.fail("Agent 执行异常: " + e.getMessage());
                }
            }

            // All steps complete — invoke DashboardAssistantAgent
            if (dashboardAgent != null && !allStepResults.isEmpty()) {
                AgentMessage summaryMessage = AgentMessage.builder()
                        .content("请汇总以下分析结果生成报告")
                        .putContext("stepResults", allStepResults)
                        .putContext("question", userInput)
                        .rounds(message.rounds() + 1)
                        .build();
                AgentMessage report = dashboardAgent.generateReply(
                        summaryMessage, this, null, null).join();
                return ActionOutput.success(report.content());
            }

            return ActionOutput.success("所有步骤已完成", Map.of("stepResults", allStepResults));
        });
    }

    // ========================================================================
    //  HITL
    // ========================================================================

    private boolean needsHumanReview(PlanStep plan) {
        return plan.getSerialNumber() == 1 && hitlEnabled;
    }

    private boolean awaitHumanDecision(String threadId, PlanStep plan) {
        this.pendingThreadId = threadId;
        this.hitlFuture = new CompletableFuture<>();
        try {
            return hitlFuture.get(300, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    public void resumeWithDecision(boolean approved) {
        if (hitlFuture != null && !hitlFuture.isDone()) {
            hitlFuture.complete(approved);
        }
    }

    public String getPendingThreadId() { return pendingThreadId; }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private List<String> buildAgentDescriptions() {
        List<String> descs = new ArrayList<>();
        for (Agent a : agents) {
            descs.add(a.name() + " (" + a.role() + "): " + a.goal());
        }
        // Also describe the planner's own available agents for context
        descs.add("DataScientist (数据科学家): 生成并执行 SQL 查询");
        descs.add("CodeAssistant (代码工程师): 执行 Python 代码进行数据分析");
        descs.add("ToolAssistant (工具专家): 调用 MCP 外部工具");
        descs.add("DashboardAssistant (报告生成者): 汇总分析结果生成报告");
        return descs;
    }

    // ========================================================================
    //  Override thinking — ManagerAgent is a pure orchestrator, no LLM needed
    // ========================================================================

    @Override
    protected String thinking(List<AgentMessage> messages) {
        // ManagerAgent doesn't need LLM — it orchestrates other agents.
        // Return a dummy ack so the pipeline proceeds to act().
        return "ORCHESTRATE";
    }

    // ========================================================================
    //  Prompt building
    // ========================================================================

    @Override
    protected String buildSystemPrompt(String observation, String memoryContext,
                                        String resourceContext, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderProfilePrompt());
        sb.append("\n");
        if (resourceContext != null && !resourceContext.isBlank()) {
            sb.append("### 上下文\n").append(resourceContext).append("\n");
        }
        return sb.toString();
    }

    @Override
    protected String buildUserPrompt(String observation, String memoryContext,
                                      String resourceContext, Map<String, Object> context) {
        return observation;
    }
}
