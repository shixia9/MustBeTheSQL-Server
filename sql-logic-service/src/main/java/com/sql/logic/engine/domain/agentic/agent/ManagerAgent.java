package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agent.core.AgentEventSinkRegistry;
import com.sql.logic.engine.domain.agent.core.AgentSseCodec;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanStep;
import com.sql.logic.engine.domain.agentic.plan.PlanStatus;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;
import com.sql.logic.engine.domain.agentic.routing.ComplexityAssessment;
import com.sql.logic.engine.domain.agentic.routing.ComplexityLevel;
import com.sql.logic.engine.domain.agentic.routing.ComplexityRouter;
import com.sql.logic.engine.domain.agentic.team.TeamMixin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Orchestration Manager Agent — the core scheduler that coordinates
 * PlannerAgent and worker Agents in a Plan → Dispatch → Execute → Report cycle.
 * <p>
 * Phase 4 enhancements:
 * <ul>
 *   <li>LLM-based complexity routing (Q3-B): simple queries skip PlannerAgent
 *       and go directly to DataScientistAgent</li>
 *   <li>Adaptive path selection (Q4-A): SIMPLE → fast path, MEDIUM/COMPLEX →
 *       full orchestration, CLARIFY → HITL clarification request</li>
 * </ul>
 */
public class ManagerAgent extends ConversableAgent implements TeamMixin {
    private static final Logger log = LoggerFactory.getLogger(ManagerAgent.class);

    public static final ProfileConfig DEFAULT_PROFILE = ProfileConfig.builder()
            .name("Manager")
            .role("编排管理者")
            .goal("根据查询复杂度智能路由：简单查询直连DataScientistAgent，复杂查询通过PlannerAgent分解后调度执行")
            .constraints(List.of(
                    "简单查询走快速路径，跳过计划生成环节",
                    "复杂查询严格按照计划步骤顺序执行",
                    "有依赖关系的步骤必须等待前置步骤完成",
                    "步骤失败时根据重试次数决定是否继续",
                    "所有步骤完成后调用 DashboardAssistantAgent 生成最终报告"
            ))
            .description("数据分析任务的总调度，支持自适应复杂度路由")
            .build();

    private final List<Agent> agents = new ArrayList<>();
    private PlanMemory planMemory;
    private PlannerAgent plannerAgent;
    private DashboardAssistantAgent dashboardAgent;
    private DataScientistAgent dataScientistAgent;
    private ComplexityRouter complexityRouter;
    private int maxRound = 100;

    // HITL support
    private boolean hitlEnabled = false;
    private String pendingThreadId;
    private CompletableFuture<Boolean> hitlFuture;

    // SSE event emission
    private AgentEventSinkRegistry eventSinkRegistry;
    private AgentSseCodec codec;

    private static final Map<String, String> NODE_NAME_MAP = Map.of(
            "DataScientist", "DATA_SCIENTIST",
            "CodeAssistant", "CODE_ASSISTANT",
            "DashboardAssistant", "DASHBOARD",
            "ToolAssistant", "TOOL_ASSISTANT",
            "Planner", "PLANNER"
    );

    private static String toNodeName(String agentName) {
        return NODE_NAME_MAP.getOrDefault(agentName, agentName.toUpperCase());
    }

    public ManagerAgent() {
        this.profile = DEFAULT_PROFILE;
    }

    public void setPlanMemory(PlanMemory planMemory) { this.planMemory = planMemory; }
    public void setPlannerAgent(PlannerAgent plannerAgent) { this.plannerAgent = plannerAgent; }
    public void setDashboardAgent(DashboardAssistantAgent dashboardAgent) { this.dashboardAgent = dashboardAgent; }
    public void setDataScientistAgent(DataScientistAgent agent) { this.dataScientistAgent = agent; }
    public void setComplexityRouter(ComplexityRouter router) { this.complexityRouter = router; }
    public void setHitlEnabled(boolean hitlEnabled) { this.hitlEnabled = hitlEnabled; }
    public void setEventSinkRegistry(AgentEventSinkRegistry registry) { this.eventSinkRegistry = registry; }
    public void setCodec(AgentSseCodec codec) { this.codec = codec; }

    @Override
    public List<Agent> getAgents() { return agents; }

    @Override
    public PlanMemory getPlanMemory() { return planMemory; }

    // ========================================================================
    //  act() — Phase 4: complexity-aware orchestration loop
    // ========================================================================

    @Override
    public CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender) {
        return CompletableFuture.supplyAsync(() -> {
            String threadId = (String) message.context().getOrDefault("threadId",
                    UUID.randomUUID().toString());
            // Read original user input from context (preserved before thinking()
            // overwrites message.content() with "ORCHESTRATE")
            String userInput = (String) message.context().getOrDefault(
                    "originalUserInput", message.content());
            List<Map<String, String>> allStepResults = new ArrayList<>();

            // Phase 4: Assess complexity and route
            ComplexityAssessment assessment = assessComplexity(message);
            log.info("[Manager] Complexity assessment: {} → {}", userInput, assessment.level());

            if (assessment.level() == ComplexityLevel.CLARIFY) {
                return handleClarification(threadId, assessment, message);
            }

            if (assessment.level() == ComplexityLevel.SIMPLE) {
                return handleSimplePath(threadId, userInput, message, allStepResults);
            }

            // MEDIUM/COMPLEX: full orchestration via PlannerAgent → Workers
            return handleFullOrchestration(threadId, userInput, message, allStepResults);
        });
    }

    /**
     * ManagerAgent orchestration IS the final action — no meaningful retry.
     * Always pass to prevent generateReply() from retrying the entire
     * orchestration loop (which would produce duplicate SSE events).
     */
    @Override
    public CompletableFuture<VerifyResult> verify(AgentMessage message, Agent sender) {
        return CompletableFuture.completedFuture(VerifyResult.PASSED);
    }

    // ========================================================================
    //  SIMPLE path: direct to DataScientistAgent (skip PlannerAgent)
    // ========================================================================

    private ActionOutput handleSimplePath(String threadId, String userInput,
                                           AgentMessage message,
                                           List<Map<String, String>> allStepResults) {
        log.info("[Manager] SIMPLE query → fast path to DataScientist");
        Agent speaker = agentByName("DataScientist");
        if (speaker == null) {
            // Fallback: try any available agent or Planner path
            return handleFullOrchestration(threadId, userInput, message, allStepResults);
        }

        // Disable multi-candidate mode for simple queries
        if (dataScientistAgent != null) {
            dataScientistAgent.setMultiCandidateMode(false);
        }

        String nodeName = toNodeName(speaker.name());
        emitSse(threadId, nodeName, "STARTED", null);

        // Create a one-step plan for progress tracking
        PlanStep simpleStep = new PlanStep(1, "DataScientist", userInput, "");
        simpleStep.setStatus(PlanStatus.RUNNING);
        planMemory.removeByConvId(threadId);
        planMemory.savePlan(threadId, List.of(simpleStep));

        AgentMessage.Builder goalBuilder = AgentMessage.builder()
                .content(userInput)
                .currentGoal(userInput)
                .putContext("plan_task_num", 1)
                .rounds(message.rounds() + 1);
        forwardAllContext(message, goalBuilder);
        AgentMessage goalMessage = goalBuilder.build();

        try {
            send(goalMessage, speaker).join();
            AgentMessage reply = speaker.generateReply(goalMessage, this, null, null).join();

            Map<String, Object> eventData = extractSubAgentData(speaker, reply);
            eventData.put("agentSuccess", reply.success());
            eventData.put("route", "fast_path");
            emitSse(threadId, nodeName, "FINISHED", eventData);

            if (reply.success()) {
                String result = reply.actionReport() != null
                        ? reply.actionReport().content() : reply.content();
                planMemory.completeTask(threadId, 1, result);
                allStepResults.add(Map.of("content", userInput, "agent", "DataScientist",
                        "result", result));
                return ActionOutput.success(result,
                        Map.of("route", "fast_path", "complexity", "SIMPLE"));
            } else {
                // Simple path failed → escalate to full orchestration
                log.info("[Manager] Fast path failed, escalating to full orchestration");
                planMemory.removeByConvId(threadId);
                if (dataScientistAgent != null) {
                    dataScientistAgent.setMultiCandidateMode(true);
                }
                return handleFullOrchestration(threadId, userInput, message, allStepResults);
            }
        } catch (Exception e) {
            log.warn("[Manager] Fast path error, escalating: {}", e.getMessage());
            planMemory.removeByConvId(threadId);
            return handleFullOrchestration(threadId, userInput, message, allStepResults);
        }
    }

    // ========================================================================
    //  CLARIFY path: request user clarification via HITL
    // ========================================================================

    private ActionOutput handleClarification(String threadId, ComplexityAssessment assessment,
                                              AgentMessage message) {
        emitSse(threadId, "MANAGER", "AWAITING_CLARIFICATION",
                Map.of("reason", assessment.reason()));
        if (hitlEnabled) {
            PlanStep clarifyStep = new PlanStep(1, "CLARIFY",
                    "请澄清: " + assessment.reason(), "");
            boolean approved = awaitHumanDecision(threadId, clarifyStep);
            if (approved) {
                // User provided clarification — re-assess with updated input
                return ActionOutput.fail("用户已澄清，请重新提交问题", true);
            }
        }
        return ActionOutput.fail("问题不够明确，请提供更多细节: " + assessment.reason(), false);
    }

    // ========================================================================
    //  MEDIUM/COMPLEX: full PlannerAgent → Workers → Dashboard pipeline
    // ========================================================================

    private ActionOutput handleFullOrchestration(String threadId, String userInput,
                                                   AgentMessage message,
                                                   List<Map<String, String>> allStepResults) {
        // Enable multi-candidate mode for complex queries
        if (dataScientistAgent != null) {
            dataScientistAgent.setMultiCandidateMode(true);
        }

        for (int round = 0; round < maxRound; round++) {
            List<PlanStep> todoPlans = planMemory.getTodoPlans(threadId);
            List<PlanStep> allPlans = planMemory.getByConvId(threadId);

            boolean allDone = !allPlans.isEmpty()
                    && allPlans.stream().allMatch(p -> p.getStatus() == PlanStatus.COMPLETED);
            if (allDone) {
                break;
            }

            // No plans → invoke PlannerAgent
            if (todoPlans.isEmpty()) {
                if (round > 3) {
                    return ActionOutput.fail("重试 3 次仍无法生成有效计划");
                }
                if (plannerAgent == null) {
                    return ActionOutput.fail("PlannerAgent 未配置，无法生成执行计划");
                }
                emitSse(threadId, "PLANNER", "STARTED", null);

                AgentMessage.Builder planBuilder = AgentMessage.builder()
                        .content(userInput)
                        .currentGoal("生成执行计划")
                        .putContext("agentDescriptions", buildAgentDescriptions())
                        .rounds(message.rounds() + 1);
                forwardAllContext(message, planBuilder);
                AgentMessage planInput = planBuilder.build();
                AgentMessage planResult = plannerAgent.generateReply(
                        planInput, this, null, null).join();

                Map<String, Object> planData = new LinkedHashMap<>();
                planData.put("agentSuccess", planResult.success());
                if (planResult.actionReport() != null) {
                    planData.put("content", planResult.actionReport().content());
                }
                emitSse(threadId, "PLANNER", "FINISHED", planData);

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

            // Phase 4: LLM-based speaker selection (Q6)
            Agent speaker = selectSpeaker(currentPlan, userInput);
            if (speaker == null) {
                return ActionOutput.fail("未找到可执行步骤的 Agent: " + currentPlan.getAgent());
            }

            List<AgentMessage> relyMessages = processRelyMessages(threadId, currentPlan);

            AgentMessage.Builder goalBuilder = AgentMessage.builder()
                    .content(currentPlan.getContent())
                    .currentGoal(currentPlan.getContent())
                    .putContext("plan_task_num", currentPlan.getSerialNumber())
                    .rounds(message.rounds() + 1);
            forwardAllContext(message, goalBuilder);
            AgentMessage goalMessage = goalBuilder.build();

            String speakerNodeName = toNodeName(speaker.name());
            emitSse(threadId, speakerNodeName, "STARTED", null);

            try {
                send(goalMessage, speaker).join();
                AgentMessage reply = speaker.generateReply(
                        goalMessage, this, relyMessages, null).join();

                Map<String, Object> eventData = extractSubAgentData(speaker, reply);
                eventData.put("agentSuccess", reply.success());
                emitSse(threadId, speakerNodeName, "FINISHED", eventData);

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
                        return ActionOutput.fail(reply.content(), true);
                    }
                }
            } catch (Exception e) {
                return ActionOutput.fail("Agent 执行异常: " + e.getMessage());
            }
        }

        // All steps complete → Dashboard
        if (dashboardAgent != null && !allStepResults.isEmpty()) {
            emitSse(threadId, "DASHBOARD", "STARTED", null);
            AgentMessage.Builder summaryBuilder = AgentMessage.builder()
                    .content("请汇总以下分析结果生成报告")
                    .putContext("stepResults", allStepResults)
                    .putContext("question", userInput)
                    .rounds(message.rounds() + 1);
            forwardAllContext(message, summaryBuilder);
            AgentMessage summaryMessage = summaryBuilder.build();
            AgentMessage report = dashboardAgent.generateReply(
                    summaryMessage, this, null, null).join();

            Map<String, Object> reportData = new LinkedHashMap<>();
            reportData.put("report", report.content());
            reportData.put("agentSuccess", report.success());
            reportData.put("route", "full_orchestration");
            emitSse(threadId, "DASHBOARD", "FINISHED", reportData);

            return ActionOutput.success(report.content(),
                    Map.of("route", "full_orchestration", "stepCount", allStepResults.size()));
        }

        return ActionOutput.success("所有步骤已完成",
                Map.of("stepResults", allStepResults, "route", "full_orchestration"));
    }

    // ========================================================================
    //  Complexity assessment
    // ========================================================================

    private ComplexityAssessment assessComplexity(AgentMessage message) {
        if (complexityRouter == null) {
            log.debug("[Manager] No ComplexityRouter bound, defaulting to MEDIUM");
            return new ComplexityAssessment(ComplexityLevel.MEDIUM,
                    "No complexity router available", null);
        }
        try {
            String userQuery = (String) message.context().getOrDefault(
                    "originalUserInput", message.content());
            String schemaSummary = (String) message.context().getOrDefault("schemaSummary", "");
            String evidenceSummary = (String) message.context().getOrDefault("evidence", "");
            Long llmConfigId = null;
            Object cid = message.context().get("llmConfigId");
            if (cid instanceof Number n) llmConfigId = n.longValue();
            else if (cid instanceof String s) {
                try { llmConfigId = Long.parseLong(s); } catch (NumberFormatException ignored) {}
            }

            return complexityRouter.assess(userQuery, schemaSummary, evidenceSummary, llmConfigId);
        } catch (Exception e) {
            log.warn("[Manager] Complexity assessment failed: {}", e.getMessage());
            return new ComplexityAssessment(ComplexityLevel.MEDIUM,
                    "Assessment error: " + e.getMessage(), null);
        }
    }

    // ========================================================================
    //  SSE event emission
    // ========================================================================

    private void emitSse(String threadId, String nodeName, String outputType,
                         Map<String, Object> data) {
        if (eventSinkRegistry == null || codec == null) return;
        Sinks.Many<String> sink = eventSinkRegistry.get(threadId);
        if (sink == null) return;
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("nodeName", nodeName);
            event.put("outputType", outputType);
            event.put("messageType", codec.messageTypeForNode(nodeName));
            event.put("sequenceNo", 0);
            if (data != null && !data.isEmpty()) {
                event.put("data", data);
            }
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(event);
            sink.tryEmitNext(json);
        } catch (Exception ignored) {}
    }

    private Map<String, Object> extractSubAgentData(Agent speaker, AgentMessage reply) {
        Map<String, Object> data = new LinkedHashMap<>();
        String name = speaker.name();
        if (reply.actionReport() != null) {
            ActionOutput ao = reply.actionReport();
            if (ao.data() != null) {
                data.putAll(ao.data());
            }
            if (ao.content() != null && !ao.content().isBlank()) {
                if ("DataScientist".equals(name)) {
                    data.putIfAbsent("sql", ao.content());
                } else if ("CodeAssistant".equals(name)) {
                    data.putIfAbsent("pythonCode", ao.content());
                }
            }
            if (!ao.isExeSuccess()) {
                data.put("errorMsg", ao.content());
            }
        }
        if (!reply.success()) {
            data.putIfAbsent("errorMsg", reply.content());
        }
        return data;
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
            return hitlFuture.get(300, TimeUnit.SECONDS);
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

    // ========================================================================
    //  Context forwarding — ensures all schema/evidence/identity keys
    //  propagate from the ManagerAgent's message to sub-agent messages
    // ========================================================================

    private static final List<String> CONTEXT_FORWARD_KEYS = List.of(
            "userId", "connectionId", "llmConfigId", "workspaceId",
            "schemaDdl", "schemaInfo", "dialect", "schemaName",
            "evidence", "conversationHistory", "userMemory",
            "agentSystemPrompt", "executionDescription",
            "threadId", "sessionId"
    );

    private void forwardAllContext(AgentMessage source, AgentMessage.Builder target) {
        if (source == null || source.context() == null) return;
        for (String key : CONTEXT_FORWARD_KEYS) {
            Object value = source.context().get(key);
            if (value != null) {
                target.putContext(key, value);
            }
        }
    }

    private List<String> buildAgentDescriptions() {
        List<String> descs = new ArrayList<>();
        for (Agent a : agents) {
            descs.add(a.name() + " (" + a.role() + "): " + a.goal());
        }
        descs.add("DataScientist (数据科学家): 生成并执行 SQL 查询");
        descs.add("CodeAssistant (代码工程师): 执行 Python 代码进行数据分析");
        descs.add("ToolAssistant (工具专家): 调用 MCP 外部工具");
        descs.add("DashboardAssistant (报告生成者): 汇总分析结果生成报告");
        return descs;
    }

    // ManagerAgent is a pure orchestrator, no LLM thinking needed
    @Override
    protected String thinking(List<AgentMessage> messages) {
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
