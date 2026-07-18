package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agentic.core.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Generates a summary report from the results of all completed plan steps.
 * Receives prior step results via rely_messages passed by ManagerAgent.
 */
public class DashboardAction implements AgentAction {

    private final PromptManager promptManager;

    public DashboardAction(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public String name() { return "dashboard"; }

    @Override
    public String description() { return "汇总所有步骤结果生成报告"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConversableAgent ca = (ConversableAgent) agent;

                @SuppressWarnings("unchecked")
                List<Map<String, String>> stepResults =
                        (List<Map<String, String>>) context.context().get("stepResults");

                StringBuilder historySummary = new StringBuilder();
                if (stepResults != null) {
                    for (Map<String, String> step : stepResults) {
                        historySummary.append("步骤: ").append(step.getOrDefault("content", ""))
                                .append("\n结果: ").append(step.getOrDefault("result", ""))
                                .append("\n\n");
                    }
                }

                Map<String, Object> vars = new HashMap<>();
                vars.put("question", context.context().getOrDefault("question", ""));
                vars.put("history_summary", historySummary.toString());
                vars.put("additional_notes", context.context().getOrDefault("additionalNotes", ""));

                String templateName = "report-generator-plain";
                String prompt = promptManager.render(templateName, vars);
                String report = ca.getLlmStrategy().generateSql(prompt, null);

                return ActionOutput.success(report, Map.of("report", report));
            } catch (Exception e) {
                return ActionOutput.fail("Dashboard generation failed: " + e.getMessage());
            }
        });
    }
}
