package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.python.PythonExecutionResult;
import com.sql.logic.engine.domain.agent.python.SimplePythonExecutor;
import com.sql.logic.engine.domain.agentic.core.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Executes Python code in a Docker sandbox via the existing {@link SimplePythonExecutor}.
 */
public class PythonExecutionAction implements AgentAction {

    private final SimplePythonExecutor pythonExecutor;

    public PythonExecutionAction(SimplePythonExecutor pythonExecutor) {
        this.pythonExecutor = pythonExecutor;
    }

    @Override
    public String name() { return "python_execution"; }

    @Override
    public String description() { return "在 Docker 沙箱中执行 Python 代码"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String code = context.content();
                if (code == null || code.isBlank()) {
                    code = (String) context.context().getOrDefault("code", "");
                }
                if (code == null || code.isBlank()) {
                    return ActionOutput.fail("No Python code to execute");
                }

                String inputJson = (String) context.context().getOrDefault("inputJson", "{}");
                PythonExecutionResult result = pythonExecutor.execute(code, inputJson);

                if (result.success()) {
                    return ActionOutput.success(result.output(), Map.of(
                            "output", result.output(),
                            "stderr", result.error()
                    ));
                } else {
                    return ActionOutput.fail("Python execution failed: " + result.error(), true);
                }
            } catch (Exception e) {
                return ActionOutput.fail("Python execution error: " + e.getMessage(), true);
            }
        });
    }
}
