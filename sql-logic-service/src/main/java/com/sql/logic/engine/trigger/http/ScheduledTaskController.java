package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.application.service.ScheduledTaskAppService;
import com.sql.logic.engine.common.dto.RunListResponse;
import com.sql.logic.engine.common.dto.ScheduledRunResponse;
import com.sql.logic.engine.common.dto.ScheduledTaskCreateRequest;
import com.sql.logic.engine.common.dto.ScheduledTaskResponse;
import com.sql.logic.engine.common.dto.ScheduledTaskUpdateRequest;
import com.sql.logic.engine.common.dto.ToggleRequest;
import com.sql.logic.engine.common.response.Result;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RESTful API for user-managed scheduled tasks.
 *
 * <p>All endpoints extract {@code userId} from the sa-token session and delegate to
 * {@link ScheduledTaskAppService}, which enforces ownership and validation.
 * {@link com.sql.logic.engine.infrastructure.exception.GlobalExceptionHandler}
 * maps {@link com.sql.logic.engine.common.exception.BizException} to its code and
 * {@link IllegalArgumentException} to 400, so this controller does no try/catch.
 *
 * <p>Replaces the legacy {@code /list}, {@code /create}, {@code /update} and
 * {@code /{id}/toggle} (no-body) paths.
 */
@RestController
@RequestMapping("/api/v1/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskAppService service;

    public ScheduledTaskController(ScheduledTaskAppService service) {
        this.service = service;
    }

    private Long getCurrentUserId() {
        String idStr = (String) StpUtil.getLoginId();
        if (idStr == null || !idStr.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid user ID in session");
        }
        return Long.valueOf(idStr);
    }

    @GetMapping
    public Result<List<ScheduledTaskResponse>> list(@RequestParam(defaultValue = "false") boolean enabledOnly) {
        return Result.success(service.list(getCurrentUserId(), enabledOnly));
    }

    @PostMapping
    public Result<ScheduledTaskResponse> create(@RequestBody ScheduledTaskCreateRequest req) {
        return Result.success(service.create(getCurrentUserId(), req));
    }

    @GetMapping("/{taskId}")
    public Result<ScheduledTaskResponse> get(@PathVariable Long taskId) {
        return Result.success(service.get(getCurrentUserId(), taskId));
    }

    @PutMapping("/{taskId}")
    public Result<ScheduledTaskResponse> update(@PathVariable Long taskId,
                                                 @RequestBody ScheduledTaskUpdateRequest req) {
        return Result.success(service.update(getCurrentUserId(), taskId, req));
    }

    @PostMapping("/{taskId}/toggle")
    public Result<ScheduledTaskResponse> toggle(@PathVariable Long taskId,
                                                @RequestBody ToggleRequest body) {
        if (body == null || body.getEnabled() == null) {
            throw new IllegalArgumentException("enabled required");
        }
        return Result.success(service.toggle(getCurrentUserId(), taskId, body.getEnabled()));
    }

    @DeleteMapping("/{taskId}")
    public Result<Void> delete(@PathVariable Long taskId) {
        service.delete(getCurrentUserId(), taskId);
        return Result.success(null);
    }

    @PostMapping("/{taskId}/run")
    public Result<ScheduledRunResponse> run(@PathVariable Long taskId) {
        return Result.success(service.manualRun(getCurrentUserId(), taskId));
    }

    @GetMapping("/{taskId}/runs")
    public Result<RunListResponse> listRuns(@PathVariable Long taskId,
                                             @RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(defaultValue = "0") int offset) {
        return Result.success(service.listRuns(getCurrentUserId(), taskId, limit, offset));
    }

    @GetMapping("/{taskId}/runs/{runId}")
    public Result<ScheduledRunResponse> getRun(@PathVariable Long taskId,
                                               @PathVariable Long runId) {
        return Result.success(service.getRun(getCurrentUserId(), taskId, runId));
    }
}
