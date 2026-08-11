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
 * Executes Python code in the sandbox via {@link SandboxExecutionService}.
 *
 * <p>The service transparently selects the best available runtime and manages per-thread
 * stateful sessions. Execution is fail-closed — when no runtime is available the
 * call returns an error rather than falling back to host execution. The
 * {@code pythonTimeout} from the agent context is forwarded to the sandbox.
 *
 * <p>An optional {@link StreamCallback} can be injected (e.g. for SSE streaming
 * in Task 7) by setting {@code "sandboxStreamCallback"} in the message context.
 */
public class PythonExecutionAction implements AgentAction {

    private final SandboxExecutionService sandboxService;

    public PythonExecutionAction(SandboxExecutionService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Override
    public String name() { return "python_execution"; }

    @Override
    public String description() { return "在沙箱中执行 Python 代码"; }

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
                String threadId = (String) context.context().getOrDefault("threadId",
                        "agent-" + UUID.randomUUID().toString().substring(0, 8));

                // Forward pythonTimeout (previously ignored by the legacy executor).
                Long timeoutSec = null;
                Object timeoutObj = context.context().get("pythonTimeout");
                if (timeoutObj instanceof Number n) {
                    timeoutSec = n.longValue();
                }

                // Optional streaming callback (set by the SSE layer in Task 7).
                StreamCallback callback = (StreamCallback) context.context().get("sandboxStreamCallback");

                ExecutionResult result = sandboxService.executePython(
                        threadId, code, inputJson, timeoutSec, callback);

                if (result.isSuccess()) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("output", result.stdout());
                    data.put("stderr", result.stderr());
                    data.put("exitCode", result.exitCode());
                    data.put("durationMs", result.durationMs());
                    data.put("sandboxRuntime", sandboxService.isSandboxAvailable() ? "sandbox" : "unavailable");
                    return ActionOutput.success(result.stdout(), data);
                } else {
                    String errorMsg = result.stderr();
                    if (errorMsg == null || errorMsg.isBlank()) {
                        errorMsg = result.displayResult() != null ? result.displayResult().error() : "Unknown error";
                    }
                    return ActionOutput.fail("Python execution failed: " + errorMsg, true);
                }
            } catch (Exception e) {
                return ActionOutput.fail("Python execution error: " + e.getMessage(), true);
            }
        });
    }
}
