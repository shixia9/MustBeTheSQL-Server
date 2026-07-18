package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.infrastructure.util.MarkdownParserUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Generates Python code via LLM for data analysis tasks.
 */
public class PythonGenerationAction implements AgentAction {

    private final PromptManager promptManager;

    public PythonGenerationAction(PromptManager promptManager) {
        this.promptManager = promptManager;
    }

    @Override
    public String name() { return "python_generation"; }

    @Override
    public String description() { return "生成 Python 数据分析代码"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConversableAgent ca = (ConversableAgent) agent;

                Map<String, Object> vars = new HashMap<>();
                vars.put("question", context.content());
                vars.put("schema_info", context.context().getOrDefault("schemaInfo", ""));
                vars.put("data_summary", context.context().getOrDefault("dataSummary", ""));

                String prompt = promptManager.render("python-generator", vars);
                String rawCode = ca.getLlmStrategy().generateSql(prompt, null);
                String code = MarkdownParserUtil.extractRawText(rawCode);

                return ActionOutput.success(code, Map.of("code", code));
            } catch (Exception e) {
                return ActionOutput.fail("Python code generation failed: " + e.getMessage());
            }
        });
    }
}
