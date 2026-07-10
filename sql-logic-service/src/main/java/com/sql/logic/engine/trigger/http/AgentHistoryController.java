package com.sql.logic.engine.trigger.http;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.application.service.AgentHistoryAppService;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.infrastructure.po.AgentExecution;
import com.sql.logic.engine.infrastructure.po.AgentExecutionStep;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agent/history")
public class AgentHistoryController {

    private final AgentHistoryAppService agentHistoryAppService;

    public AgentHistoryController(AgentHistoryAppService agentHistoryAppService) {
        this.agentHistoryAppService = agentHistoryAppService;
    }

    @GetMapping
    public Result<Page<AgentExecution>> listHistories(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long workspaceId,
            @RequestParam(required = false) Long conversationId) {
        try {
            String userIdStr = (String) StpUtil.getLoginId();
            if (userIdStr == null || !userIdStr.matches("\\d+")) {
                return Result.error(400, "Invalid user ID in session");
            }
            Long userId = Long.valueOf(userIdStr);
            Page<AgentExecution> result = agentHistoryAppService.listExecutions(userId, page, size, keyword, workspaceId, conversationId);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public Result<AgentExecution> getHistory(@PathVariable Long id) {
        try {
            AgentExecution execution = agentHistoryAppService.getExecution(id);
            if (execution == null) {
                return Result.error(404, "History not found");
            }
            return Result.success(execution);
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @GetMapping("/{id}/steps")
    public Result<List<AgentExecutionStep>> getHistorySteps(@PathVariable Long id) {
        try {
            List<AgentExecutionStep> steps = agentHistoryAppService.getSteps(id);
            return Result.success(steps);
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public Result<Void> deleteHistory(@PathVariable Long id) {
        try {
            agentHistoryAppService.deleteExecution(id);
            return Result.success(null);
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }
}
