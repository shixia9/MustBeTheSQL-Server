package com.sql.logic.engine.domain.agentic.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.core.AgentEventSinkRegistry;
import com.sql.logic.engine.domain.agent.core.AgentSseCodec;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.core.bus.AgentDispatcher;
import com.sql.logic.engine.domain.agentic.core.bus.BusOrchestrationMode;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // Message-bus integration. When null, dispatch falls back to the
    // legacy direct generateReply call (preserves existing ManagerAgentTest
    // wiring that does not set a dispatcher).
    private AgentDispatcher dispatcher;

    private static final Map<String, String> NODE_NAME_MAP = Map.of(
            "DataScientist", "DATA_SCIENTIST",
            "CodeAssistant", "CODE_ASSISTANT",
            "DashboardAssistant", "DASHBOARD",
            "ToolAssistant", "TOOL_ASSISTANT",
            "Planner", "PLANNER"
    );

    /** Serializes structured step results (e.g. SQL rows) into python stdin JSON. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Extracts the content inside a {@code ```html ... ```} code fence. */
    private static final Pattern HTML_FENCE_RE =
            Pattern.compile("```html\\s*\\n?(.*?)```", Pattern.DOTALL);

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

    /**
     * Message-bus integration. When unset, {@link #dispatchToWorker} falls back to a direct
     * {@code generateReply} so existing test wiring (no dispatcher) keeps working unchanged.
     */
    public void setDispatcher(AgentDispatcher dispatcher) { this.dispatcher = dispatcher; }

    /** The active bus-orchestration mode, or {@code null} when no dispatcher is bound. */
    public BusOrchestrationMode busOrchestrationMode() {
        return dispatcher != null ? dispatcher.mode() : null;
    }

    @Override
    public List<Agent> getAgents() { return agents; }

    @Override
    public PlanMemory getPlanMemory() { return planMemory; }

    // ========================================================================
    //  Message-bus integration — routes the goal to a worker via the
    //  configured AgentDispatcher (OFF/BYPASS/SWITCH), or falls back to a direct
    //  generateReply when no dispatcher is bound. Every worker invocation in the
    //  orchestration paths below goes through this single chokepoint so the bus
    //  integration is localised and reversible.
    // ========================================================================

    private CompletableFuture<AgentMessage> dispatchToWorker(Agent worker, AgentMessage goal,
                                                              List<AgentMessage> relyMessages) {
        if (worker == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("dispatch target agent is null"));
        }
        if (dispatcher == null) {
            return worker.generateReply(goal, this, relyMessages, null);
        }
        return dispatcher.dispatch(this, worker, goal, relyMessages);
    }

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

            // Direct tool invocation shortcut — when the user picked a tool
            // from the "/" command palette, the request carries a non-empty
            // toolInvocation payload (toolName + optional args). Route directly
            // to ToolAssistantAgent, skipping complexity assessment and Planner.
            if (hasDirectToolInvocation(message)) {
                log.info("[Manager] Direct toolInvocation detected → short-circuit to ToolAssistant");
                return handleToolInvocationPath(threadId, userInput, message, allStepResults);
            }

            // Phase 4: Assess complexity and route
            ComplexityAssessment assessment = assessComplexity(message);
            log.info("[Manager] Complexity assessment: {} → {}", userInput, assessment.level());

            if (assessment.level() == ComplexityLevel.CLARIFY) {
                return handleClarification(threadId, assessment, message);
            }

            if (assessment.level() == ComplexityLevel.CHITCHAT) {
                return handleChitchatPath(threadId, userInput, message);
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
        emitPlanSnapshot(threadId);

        AgentMessage.Builder goalBuilder = AgentMessage.builder()
                .content(userInput)
                .currentGoal(userInput)
                .putContext("plan_task_num", 1)
                .rounds(message.rounds() + 1);
        forwardAllContext(message, goalBuilder);
        AgentMessage goalMessage = goalBuilder.build();

        try {
            send(goalMessage, speaker).join();
            AgentMessage reply = dispatchToWorker(speaker, goalMessage, null).join();

            Map<String, Object> eventData = extractSubAgentData(speaker, reply);
            eventData.put("agentSuccess", reply.success());
            eventData.put("route", "fast_path");
            emitSse(threadId, nodeName, "FINISHED", eventData);

            if (reply.success()) {
                String result = reply.actionReport() != null
                        ? reply.actionReport().content() : reply.content();
                planMemory.completeTask(threadId, 1, result);
                emitPlanSnapshot(threadId);
                allStepResults.add(Map.of("content", userInput, "agent", "DataScientist",
                        "result", result));

                // Generate a summary for the left-panel timeline via DashboardAgent.
                // The agent outputs JSON chart items + Markdown + optional HTML;
                // the frontend StepTimeline is responsible for folding JSON/HTML
                // sections and showing only the Markdown text in the left panel.
                if (dashboardAgent != null && !allStepResults.isEmpty()) {
                    try {
                        emitSse(threadId, "DASHBOARD", "STARTED", null);
                        AgentMessage.Builder summaryBuilder = AgentMessage.builder()
                                .content("请汇总以下分析结果生成报告")
                                .putContext("stepResults", allStepResults)
                                .putContext("question", userInput)
                                .putContext("htmlReport", false)
                                .rounds(message.rounds() + 1);
                        forwardAllContext(message, summaryBuilder);
                        AgentMessage summaryMessage = summaryBuilder.build();
                        AgentMessage report = dispatchToWorker(dashboardAgent, summaryMessage, null).join();

                        Map<String, Object> reportData = new LinkedHashMap<>();
                        reportData.put("content", report.content());
                        reportData.put("agentSuccess", report.success());
                        reportData.put("route", "fast_path");
                        emitSse(threadId, "DASHBOARD", "FINISHED", reportData);
                    } catch (Exception e) {
                        log.warn("[Manager] SIMPLE summary failed: {}", e.getMessage());
                    }
                }

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
    //  Direct tool invocation path — skip Planner, route straight to
    //  ToolAssistantAgent. The toolInvocation payload (toolName + args) was
    //  placed in context by AgentStateBridge and forwarded to the sub-agent
    //  message via CONTEXT_FORWARD_KEYS; McpToolAction reads it as the source
    //  of truth.
    // ========================================================================

    private ActionOutput handleToolInvocationPath(String threadId, String userInput,
                                                  AgentMessage message,
                                                  List<Map<String, String>> allStepResults) {
        log.info("[Manager] TOOL_INVOCATION → direct path to ToolAssistant");
        Agent speaker = agentByName("ToolAssistant");
        if (speaker == null) {
            // No ToolAssistant registered — fall back to full orchestration so
            // the request still produces a meaningful response.
            log.warn("[Manager] ToolAssistant not registered, falling back to full orchestration");
            return handleFullOrchestration(threadId, userInput, message, allStepResults);
        }

        String nodeName = toNodeName(speaker.name());
        emitSse(threadId, nodeName, "STARTED", null);

        // Create a one-step plan for progress tracking.
        PlanStep toolStep = new PlanStep(1, "ToolAssistant", userInput, "");
        toolStep.setStatus(PlanStatus.RUNNING);
        planMemory.removeByConvId(threadId);
        planMemory.savePlan(threadId, List.of(toolStep));
        emitPlanSnapshot(threadId);

        AgentMessage.Builder goalBuilder = AgentMessage.builder()
                .content(userInput)
                .currentGoal(userInput)
                .putContext("plan_task_num", 1)
                .rounds(message.rounds() + 1);
        forwardAllContext(message, goalBuilder);
        AgentMessage goalMessage = goalBuilder.build();

        try {
            send(goalMessage, speaker).join();
            AgentMessage reply = dispatchToWorker(speaker, goalMessage, null).join();

            Map<String, Object> eventData = extractSubAgentData(speaker, reply);
            eventData.put("agentSuccess", reply.success());
            eventData.put("route", "tool_invocation");
            emitSse(threadId, nodeName, "FINISHED", eventData);

            if (reply.success()) {
                String result = reply.actionReport() != null
                        ? reply.actionReport().content() : reply.content();
                planMemory.completeTask(threadId, 1, result);
                emitPlanSnapshot(threadId);
                allStepResults.add(Map.of("content", userInput, "agent", "ToolAssistant",
                        "result", result));
                return ActionOutput.success(result,
                        Map.of("route", "tool_invocation"));
            }
            // Failure: surface the error directly (no escalation — the user
            // explicitly asked for a tool call, so retrying via Planner would
            // be confusing).
            return ActionOutput.fail(reply.content(), true);
        } catch (Exception e) {
            log.warn("[Manager] Tool invocation path error: {}", e.getMessage());
            return ActionOutput.fail("Tool invocation failed: " + e.getMessage(), true);
        }
    }

    /**
     * Whether the current message carries a direct tool invocation payload
     * (T8.1). The payload is a non-empty Map placed in context under
     * {@code toolInvocation} by AgentStateBridge.
     */
    private boolean hasDirectToolInvocation(AgentMessage message) {
        if (message == null || message.context() == null) return false;
        Object v = message.context().get("toolInvocation");
        return v instanceof Map<?, ?> m && !m.isEmpty();
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
    //  CHITCHAT path: ManagerAgent answers directly via LLM (no SQL pipeline,
    //  no Dashboard summary). Produces a natural-language conversational
    //  response — mirrors the mewcode Coordinator pattern where the entry
    //  agent "answers questions directly when possible — don't delegate work
    //  you can handle without tools".
    // ========================================================================

    private ActionOutput handleChitchatPath(String threadId, String userInput,
                                             AgentMessage message) {
        log.info("[Manager] CHITCHAT → direct LLM answer (no pipeline)");
        var strategy = resolveLlmStrategy();
        if (strategy == null) {
            return ActionOutput.fail("No LLM available for chitchat reply", true);
        }

        String systemPrompt = """
                你是一个数据分析平台的 AI 助手。请用自然语言直接回答用户的问题。
                注意事项：
                - 不要生成 SQL、代码、JSON 或报告结构
                - 不要使用"总结"、"分析报告"等措辞，保持对话式回答
                - 如果用户问候，自然回应即可
                - 如果用户询问能力，简要介绍你能帮助进行数据查询、分析、生成报告和图表
                - 回答简洁明了，不要过度展开
                """;
        String conversationHistory = (String) message.context().getOrDefault(
                "conversationHistory", "");
        String prompt = systemPrompt
                + (conversationHistory.isBlank() ? "" : "\n### 对话历史\n" + conversationHistory + "\n")
                + "\n用户问题: " + userInput;

        String answer;
        try {
            answer = strategy.chat(prompt);
        } catch (Exception e) {
            log.warn("[Manager] Chitchat LLM call failed: {}", e.getMessage());
            return ActionOutput.fail("回答生成失败: " + e.getMessage(), true);
        }
        if (answer == null || answer.isBlank()) {
            answer = "你好，我是数据分析助手，可以帮你查询数据、分析问题和生成报告。请问有什么可以帮您的？";
        }

        // No MANAGER STARTED emitted — frontend filters MANAGER cards and shows
        // "thinking..." while steps are empty. Emit only the DASHBOARD FINISHED
        // event so the existing lastDashboardStep renderer picks up the answer.
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("content", answer);
        eventData.put("route", "chitchat");
        eventData.put("agentSuccess", true);
        emitSse(threadId, "DASHBOARD", "FINISHED", eventData);

        return ActionOutput.success(answer, Map.of("route", "chitchat"));
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

        // serialNumber → SQL result rows JSON (fed as python stdin to dependent steps).
        Map<Integer, String> stepRowsJson = new HashMap<>();

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
                AgentMessage planResult = dispatchToWorker(plannerAgent, planInput, null).join();

                Map<String, Object> planData = new LinkedHashMap<>();
                planData.put("agentSuccess", planResult.success());
                if (planResult.actionReport() != null) {
                    planData.put("content", planResult.actionReport().content());
                }
                emitSse(threadId, "PLANNER", "FINISHED", planData);
                emitPlanSnapshot(threadId);

                if (!planResult.success()) {
                    return ActionOutput.fail("PlannerAgent 计划生成失败: " + planResult.content());
                }
                continue;
            }

            // Take the first TODO plan step
            PlanStep currentPlan = todoPlans.get(0);
            currentPlan.setStatus(PlanStatus.RUNNING);
            emitPlanSnapshot(threadId);

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
            // Feed the upstream SQL result rows as python stdin for dependent
            // code steps (CodeAssistant reads context "inputJson").
            String stepInputJson = resolveStepInputJson(currentPlan, stepRowsJson);
            if (stepInputJson != null) {
                goalBuilder.putContext("inputJson", stepInputJson);
            }
            AgentMessage goalMessage = goalBuilder.build();

            String speakerNodeName = toNodeName(speaker.name());
            emitSse(threadId, speakerNodeName, "STARTED", null);

            try {
                send(goalMessage, speaker).join();
                AgentMessage reply = dispatchToWorker(speaker, goalMessage, relyMessages).join();

                Map<String, Object> eventData = extractSubAgentData(speaker, reply);
                eventData.put("agentSuccess", reply.success());
                emitSse(threadId, speakerNodeName, "FINISHED", eventData);

                if (reply.success()) {
                    String result = reply.actionReport() != null
                            ? reply.actionReport().content() : reply.content();
                    planMemory.completeTask(threadId, currentPlan.getSerialNumber(), result);
                    emitPlanSnapshot(threadId);
                    allStepResults.add(Map.of(
                            "content", currentPlan.getContent(),
                            "agent", currentPlan.getAgent(),
                            "result", result
                    ));
                    // Preserve structured SQL rows so dependent python/code steps can
                    // receive them as stdin (inputJson) during sandbox execution.
                    Map<String, Object> aData = reply.actionReport() != null
                            ? reply.actionReport().data() : null;
                    if (aData != null && aData.get("rows") instanceof List<?> rows && !rows.isEmpty()) {
                        try {
                            stepRowsJson.put(currentPlan.getSerialNumber(),
                                    MAPPER.writeValueAsString(rows));
                        } catch (Exception ignored) {
                            // non-serializable rows — skip; execution degrades to empty input
                        }
                    }
                } else {
                    if (currentPlan.getRetryTimes() < currentPlan.getMaxRetryTimes()) {
                        planMemory.updateTask(threadId, currentPlan.getSerialNumber(),
                                PlanStatus.TODO, currentPlan.getRetryTimes() + 1,
                                speaker.name(), reply.content());
                        emitPlanSnapshot(threadId);
                        continue;
                    } else {
                        planMemory.updateTask(threadId, currentPlan.getSerialNumber(),
                                PlanStatus.FAILED, currentPlan.getRetryTimes() + 1,
                                speaker.name(), reply.content());
                        emitPlanSnapshot(threadId);
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
                    .putContext("htmlReport", true)
                    .rounds(message.rounds() + 1);
            forwardAllContext(message, summaryBuilder);
            AgentMessage summaryMessage = summaryBuilder.build();
            AgentMessage report = dispatchToWorker(dashboardAgent, summaryMessage, null).join();

            Map<String, Object> reportData = new LinkedHashMap<>();
            reportData.put("agentSuccess", report.success());
            reportData.put("route", "full_orchestration");
            String fullReport = report.content();
            String htmlBlock = extractHtmlFence(fullReport);
            if (htmlBlock != null && !htmlBlock.isBlank()) {
                reportData.put("htmlContent", htmlBlock);
                reportData.put("report", HTML_FENCE_RE.matcher(fullReport).replaceAll("").trim());
            } else {
                reportData.put("report", fullReport);
            }
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
            String json = MAPPER.writeValueAsString(event);
            sink.tryEmitNext(json);
        } catch (Exception ignored) {}
    }

    /**
     * Emit a full plan snapshot as a {@code PLAN_UPDATED} SSE event so the
     * frontend can render a real-time TODO list of the Planner's multi-step
     * plan. Called at every plan-state transition (generation, step start,
     * completion, failure/retry). Best-effort: any failure is swallowed.
     */
    void emitPlanSnapshot(String threadId) {
        if (planMemory == null) return;
        List<PlanStep> steps = planMemory.getByConvId(threadId);
        if (steps == null || steps.isEmpty()) return;
        try {
            List<Map<String, Object>> stepList = new ArrayList<>();
            int completed = 0;
            int failed = 0;
            for (PlanStep s : steps) {
                Map<String, Object> dto = new LinkedHashMap<>();
                dto.put("serialNumber", s.getSerialNumber());
                dto.put("agent", s.getAgent());
                dto.put("content", s.getContent());
                dto.put("rely", s.getRely());
                dto.put("status", s.getStatus() == null ? null : s.getStatus().name());
                String r = s.getResult();
                if (r != null && r.length() > 500) {
                    r = r.substring(0, 500) + "…";
                }
                dto.put("result", r);
                dto.put("retryTimes", s.getRetryTimes());
                stepList.add(dto);
                if (s.getStatus() == PlanStatus.COMPLETED) completed++;
                else if (s.getStatus() == PlanStatus.FAILED) failed++;
            }
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("steps", stepList);
            snapshot.put("totalSteps", steps.size());
            snapshot.put("completedSteps", completed);
            snapshot.put("failedSteps", failed);
            emitSse(threadId, "PLAN", "PLAN_UPDATED", snapshot);
        } catch (Exception ignored) {
            // best-effort: never break orchestration on SSE encoding
        }
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
                // Always include content as a generic field — carries vis-db-chart,
                // vis-dashboard code fences and other text the frontend needs.
                data.put("content", ao.content());
                // Backward-compatible special keys
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
    //  HTML fence extraction
    // ========================================================================

    /**
     * Extract the content inside a {@code ```html ... ```} code fence from a
     * dashboard report. Returns {@code null} when no HTML block is present.
     */
    static String extractHtmlFence(String text) {
        if (text == null) return null;
        Matcher m = HTML_FENCE_RE.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

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
            "threadId", "sessionId",
            // T8.3: forward the direct tool-invocation payload so McpToolAction
            // can read toolName/args as the source of truth.
            "toolInvocation"
            // htmlReport is intentionally NOT forwarded — each path decides:
            //   SIMPLE → false, MEDIUM/COMPLEX → true
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

    /**
     * Resolve the rows JSON of the first completed dependency step (per
     * {@link PlanStep#getRely()}) — used as python stdin for code steps.
     * Returns {@code null} when the step has no dependencies or no rows were captured.
     */
    private String resolveStepInputJson(PlanStep plan, Map<Integer, String> stepRowsJson) {
        if (plan == null || plan.getRely() == null || plan.getRely().isBlank()
                || stepRowsJson.isEmpty()) {
            return null;
        }
        for (String p : plan.getRely().split(",")) {
            try {
                String rows = stepRowsJson.get(Integer.parseInt(p.trim()));
                if (rows != null) {
                    return rows;
                }
            } catch (NumberFormatException ignored) {
                // non-numeric dependency — skip
            }
        }
        return null;
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

    // ManagerAgent's thinking() returns the constant "ORCHESTRATE" — emitting
    // that as a THINKING event would be pure noise. Suppress it so only worker
    // agents (DataScientist, CodeAssistant, etc.) stream their real LLM reasoning.
    @Override
    protected boolean shouldEmitThinking() {
        return false;
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
