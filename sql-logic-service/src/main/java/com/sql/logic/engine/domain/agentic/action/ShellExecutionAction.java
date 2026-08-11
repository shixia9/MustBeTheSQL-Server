package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.sandbox.SandboxExecutionService;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionResult;
import com.sql.logic.engine.domain.sandbox.execution.StreamCallback;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Executes shell/bash commands in the sandbox via {@link SandboxExecutionService}.
 *
 * <p>New action complementing {@link PythonExecutionAction} — enables the agent
 * system to run shell commands (e.g. file operations, data processing pipelines)
 * in the same isolated sandbox environment. Shell execution always uses the new
 * sandbox runtime (no legacy fallback); if no runtime is available, it returns
 * an error.
 *
 * <p>Security: shell commands are validated by {@code SecurityUtils} (bash
 * blacklist) inside {@code LocalSandboxSession}, and isolated by container
 * hardening inside {@code DockerSandboxSession}.
 */
public class ShellExecutionAction implements AgentAction {

    private final SandboxExecutionService sandboxService;

    public ShellExecutionAction(SandboxExecutionService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Override
    public String name() { return "shell_execution"; }

    @Override
    public String description() { return "在沙箱中执行 Shell/Bash 命令"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String code = context.content();
                if (code == null || code.isBlank()) {
                    code = (String) context.context().getOrDefault("command", "");
                }
                if (code == null || code.isBlank()) {
                    return ActionOutput.fail("No shell command to execute");
                }

                String threadId = (String) context.context().getOrDefault("threadId",
                        "agent-" + UUID.randomUUID().toString().substring(0, 8));

                Long timeoutSec = null;
                Object timeoutObj = context.context().get("shellTimeout");
                if (timeoutObj instanceof Number n) {
                    timeoutSec = n.longValue();
                }

                StreamCallback callback = (StreamCallback) context.context().get("sandboxStreamCallback");

                ExecutionResult result = sandboxService.executeShell(
                        threadId, code, timeoutSec, callback);

                if (result.isSuccess()) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("output", result.stdout());
                    data.put("stderr", result.stderr());
                    data.put("exitCode", result.exitCode());
                    data.put("durationMs", result.durationMs());
                    return ActionOutput.success(result.stdout(), data);
                } else {
                    String errorMsg = result.stderr();
                    if (errorMsg == null || errorMsg.isBlank()) {
                        errorMsg = "Shell execution failed (exit " + result.exitCode() + ")";
                    }
                    return ActionOutput.fail("Shell execution failed: " + errorMsg, true);
                }
            } catch (Exception e) {
                return ActionOutput.fail("Shell execution error: " + e.getMessage(), true);
            }
        });
    }
}
