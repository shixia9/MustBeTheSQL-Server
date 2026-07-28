package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.engine.application.service.ScheduledTaskAppService;
import com.sql.logic.engine.common.dto.ScheduledTaskCreateRequest;
import com.sql.logic.engine.common.dto.ScheduledTaskResponse;
import com.sql.logic.engine.common.dto.ScheduledTaskUpdateRequest;
import com.sql.logic.engine.common.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * CRUD + toggle for user-managed scheduled tasks.
 */
@RestController
@RequestMapping("/api/v1/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskAppService scheduledTaskAppService;

    public ScheduledTaskController(ScheduledTaskAppService scheduledTaskAppService) {
        this.scheduledTaskAppService = scheduledTaskAppService;
    }

    private Long getCurrentUserId() {
        String idStr = (String) StpUtil.getLoginId();
        if (idStr == null || !idStr.matches("\\d+")) {
            throw new IllegalArgumentException("Invalid user ID in session");
        }
        return Long.valueOf(idStr);
    }

    @GetMapping("/list")
    public Result<List<ScheduledTaskResponse>> list() {
        return Result.success(scheduledTaskAppService.list(getCurrentUserId()));
    }

    @PostMapping("/create")
    public Result<ScheduledTaskResponse> create(@RequestBody ScheduledTaskCreateRequest request) {
        return Result.success(scheduledTaskAppService.create(getCurrentUserId(), request));
    }

    @PutMapping("/update")
    public Result<ScheduledTaskResponse> update(@RequestBody ScheduledTaskUpdateRequest request) {
        return Result.success(scheduledTaskAppService.update(getCurrentUserId(), request));
    }

    @DeleteMapping("/{taskId}")
    public Result<Void> delete(@PathVariable Long taskId) {
        scheduledTaskAppService.delete(getCurrentUserId(), taskId);
        return Result.success(null);
    }

    @PutMapping("/{taskId}/toggle")
    public Result<ScheduledTaskResponse> toggle(@PathVariable Long taskId) {
        return Result.success(scheduledTaskAppService.toggle(getCurrentUserId(), taskId));
    }
}
