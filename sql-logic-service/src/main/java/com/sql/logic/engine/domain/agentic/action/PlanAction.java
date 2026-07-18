package com.sql.logic.engine.domain.agentic.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.core.AgentMemory.TaskStatus;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Plan action for PlannerAgent — generates structured PlanStep list from LLM output.
 */
public class PlanAction implements AgentAction {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final PlanMemory planMemory;
    private final List<Map<String, String>> availableAgents;

    public PlanAction(PlanMemory planMemory, List<Map<String, String>> availableAgents) {
        this.planMemory = planMemory;
        this.availableAgents = availableAgents;
    }

    @Override
    public String name() { return "plan"; }

    @Override
    public String description() { return "生成任务执行计划"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConversableAgent ca = (ConversableAgent) agent;
                String llmOutput = context.content();
                if (llmOutput == null || llmOutput.isBlank()) {
                    return ActionOutput.fail("Planner LLM output is empty");
                }

                List<PlanInput> planInputs = parsePlanInputs(llmOutput);
                if (planInputs.isEmpty()) {
                    return ActionOutput.fail("Failed to parse plan from LLM output");
                }

                String convId = (String) context.context().getOrDefault("threadId", "default");
                List<PlanStep> steps = new ArrayList<>();
                for (PlanInput pi : planInputs) {
                    PlanStep step = new PlanStep(pi.serial_number, pi.agent, pi.content, pi.rely);
                    steps.add(step);
                }

                planMemory.removeByConvId(convId);
                planMemory.savePlan(convId, steps);

                // Record task progress
                if (ca.getMemory() != null) {
                    for (PlanStep step : steps) {
                        ca.getMemory().recordTaskProgress(new AgentMemory.TaskProgressEntry(
                                step.getSerialNumber(), step.getContent(), "PLANNING", TaskStatus.DONE,
                                step.getAgent() + ": " + step.getContent()
                        ));
                    }
                }

                StringBuilder view = new StringBuilder("计划已生成:\n");
                for (PlanStep step : steps) {
                    view.append("- ").append(step.getSerialNumber()).append(". ")
                            .append(step.getContent()).append(" [").append(step.getAgent()).append("]");
                    if (step.getRely() != null && !step.getRely().isBlank()) {
                        view.append(" (依赖步骤: ").append(step.getRely()).append(")");
                    }
                    view.append("\n");
                }

                return ActionOutput.success(view.toString(),
                        Map.of("steps", steps, "stepCount", steps.size()));
            } catch (Exception e) {
                return ActionOutput.fail("Plan generation failed: " + e.getMessage());
            }
        });
    }

    public List<PlanInput> parsePlanInputs(String llmOutput) {
        try {
            // Try direct JSON array
            return objectMapper.readValue(llmOutput, new TypeReference<List<PlanInput>>() {});
        } catch (Exception e1) {
            try {
                // Try extracting JSON array from markdown/text
                String json = extractJsonArray(llmOutput);
                if (json != null) {
                    return objectMapper.readValue(json, new TypeReference<List<PlanInput>>() {});
                }
                // Try extracting plan from object wrapper
                String objJson = extractJsonObject(llmOutput);
                if (objJson != null) {
                    var node = objectMapper.readTree(objJson);
                    var planNode = node.get("execution_plan");
                    if (planNode == null) planNode = node.get("plan");
                    if (planNode != null) {
                        return objectMapper.readValue(planNode.traverse(),
                                new TypeReference<List<PlanInput>>() {});
                    }
                }
            } catch (Exception e2) {
                return List.of();
            }
        }
        return List.of();
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }

    /**
     * LLM output schema for a single plan step.
     */
    public static class PlanInput {
        public int serial_number;
        public String agent;
        public String content;
        public String rely;

        public PlanInput() {}
        public int getSerial_number() { return serial_number; }
        public String getAgent() { return agent; }
        public String getContent() { return content; }
        public String getRely() { return rely; }
    }
}
