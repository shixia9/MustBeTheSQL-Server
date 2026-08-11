package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.common.dto.SandboxConfigureRequest;
import com.sql.logic.engine.common.dto.SandboxConnectRequest;
import com.sql.logic.engine.common.dto.SandboxDisconnectRequest;
import com.sql.logic.engine.common.dto.SandboxExecuteRequest;
import com.sql.logic.engine.common.dto.SandboxMethodsResponse;
import com.sql.logic.engine.common.dto.SandboxRunRequest;
import com.sql.logic.engine.common.dto.SandboxSessionResponse;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.domain.sandbox.control.SandboxControlService;
import com.sql.logic.engine.domain.sandbox.display.DisplayResult;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API for sandboxed code execution.
 *
 * <p>Exposes the full session lifecycle: connect → execute → configure → disconnect,
 * plus status, list, and get-file. All endpoints delegate to
 * {@link SandboxControlService}, which enforces per-session serialisation and
 * runtime selection.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/v1/sandbox/connect} — create a session</li>
 *   <li>{@code POST /api/v1/sandbox/execute} — execute code (synchronous)</li>
 *   <li>{@code POST /api/v1/sandbox/configure} — install dependencies</li>
 *   <li>{@code POST /api/v1/sandbox/disconnect} — destroy a session</li>
 *   <li>{@code GET  /api/v1/sandbox/status} — runtime health check</li>
 *   <li>{@code GET  /api/v1/sandbox/list} — list active sessions</li>
 *   <li>{@code GET  /api/v1/sandbox/get-file} — retrieve a file from a session</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sandbox")
public class SandboxController {

    private static final Logger log = LoggerFactory.getLogger(SandboxController.class);

    private final SandboxControlService controlService;

    public SandboxController(SandboxControlService controlService) {
        this.controlService = controlService;
    }

    @PostMapping("/connect")
    public Result<String> connect(@RequestBody SandboxConnectRequest req) {
        String sessionId = controlService.connect(req.getLanguage(), req.getTimeout());
        return Result.success(sessionId);
    }

    @PostMapping("/execute")
    public Result<ExecutionResult> execute(@RequestBody SandboxExecuteRequest req) {
        if (req.getCode() == null || req.getCode().isBlank()) {
            return Result.error(400, "Code is required");
        }
        if (req.getSessionId() == null || req.getSessionId().isBlank()) {
            return Result.error(400, "Session ID is required");
        }
        // REST execute is synchronous (no streaming callback). Agent integration
        // (SandboxExecutionService) calls controlService.execute() directly with a
        // StreamCallback for SSE streaming.
        ExecutionResult result = controlService.execute(req.getSessionId(), req.getCode(), null);
        return Result.success(result);
    }

    /**
     * One-shot manual execution — connects an ephemeral session, runs the code,
     * and disconnects in a single synchronous call. Backs the frontend "Run"
     * button on Python code blocks. No prior {@code /connect} needed.
     */
    @PostMapping("/run")
    public Result<ExecutionResult> run(@RequestBody SandboxRunRequest req) {
        if (req.getCode() == null || req.getCode().isBlank()) {
            return Result.error(400, "Code is required");
        }
        String language = (req.getLanguage() == null || req.getLanguage().isBlank())
                ? "python" : req.getLanguage();
        try {
            // manual() handles connect→execute→disconnect in one call.
            ExecutionResult result = controlService.manual(language, req.getCode(), req.getTimeout());
            return Result.success(result);
        } catch (Exception e) {
            log.warn("[SandboxController] /run failed (language={}): {}", language, e.getMessage());
            return Result.error(500, "Sandbox execution failed: " + e.getMessage());
        }
    }

    @PostMapping("/configure")
    public Result<ExecutionResult> configure(@RequestBody SandboxConfigureRequest req) {
        ExecutionResult result = controlService.configure(
                req.getSessionId(), req.getDependencies(), req.getConfigInfo());
        return Result.success(result);
    }

    @PostMapping("/disconnect")
    public Result<Boolean> disconnect(@RequestBody SandboxDisconnectRequest req) {
        boolean ok = controlService.disconnect(req.getSessionId());
        return Result.success(ok);
    }

    @GetMapping("/status")
    public Result<Map<String, Object>> status() {
        return Result.success(controlService.status());
    }

    @GetMapping("/list")
    public Result<List<SandboxSessionResponse>> list() {
        return Result.success(controlService.list());
    }

    @GetMapping("/get-file")
    public Result<DisplayResult> getFile(@RequestParam String sessionId,
                                         @RequestParam String filename) {
        DisplayResult result = controlService.getFile(sessionId, filename);
        if (result == null) {
            return Result.error(404, "File not found: " + filename);
        }
        return Result.success(result);
    }

    /**
     * Capability discovery — surfaces supported languages, selected runtime, VNC
     * availability, and other capability flags.
     */
    @GetMapping("/methods")
    public Result<SandboxMethodsResponse> methods() {
        return Result.success(controlService.methods());
    }
}
