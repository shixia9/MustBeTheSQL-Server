package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agentic.core.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Analyzes Python execution results via LLM to determine if the output
 * satisfies the original analysis goal.
 */
public class PythonAnalyzeAction implements AgentAction {

    private final PromptManager promptManager;

    public PythonAnalyzeAction(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public String name() { return "python_analysis"; }

    @Override
    public String description() { return "分析 Python 执行结果"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConversableAgent ca = (ConversableAgent) agent;

                Map<String, Object> vars = new HashMap<>();
                vars.put("question", context.context().getOrDefault("question", ""));
                vars.put("python_result", context.content());

                String prompt = promptManager.render("python-analyze", vars);
                String analysis = ca.resolveLlmStrategy().generateSql(prompt, null);

                return ActionOutput.success(analysis, Map.of("analysis", analysis));
            } catch (Exception e) {
                return ActionOutput.fail("Python analysis failed: " + e.getMessage());
            }
        });
    }
}
