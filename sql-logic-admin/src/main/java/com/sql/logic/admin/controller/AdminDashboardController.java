package com.sql.logic.admin.controller;

import com.sql.logic.admin.service.AdminUserService;
import com.sql.logic.engine.common.dubbo.AdminDataDTOs;
import com.sql.logic.engine.common.dubbo.AdminDataService;
import com.sql.logic.engine.common.response.Result;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminDashboardController {

    @DubboReference
    private AdminDataService adminDataService;

    private final AdminUserService adminUserService;

    public AdminDashboardController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        AdminDataDTOs.DashboardStats stats = adminDataService.getDashboardStats();
        stats.setTotalAdmins(adminUserService.listAdmins().size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalUsers", stats.getTotalUsers());
        result.put("totalAdmins", stats.getTotalAdmins());
        result.put("totalExecutions", stats.getTotalExecutions());
        result.put("activeToday", stats.getActiveToday());
        result.put("timestamp", System.currentTimeMillis());
        return Result.success(result);
    }

    /** Workflow execution overview — recent agent_execution records. */
    @GetMapping("/workflows")
    public Result<AdminDataDTOs.PageResult<Map<String, Object>>> getWorkflowOverview(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        AdminDataDTOs.PageResult<AdminDataDTOs.WorkflowOverviewDTO> result =
                adminDataService.getWorkflowOverview(page, size, keyword);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AdminDataDTOs.WorkflowOverviewDTO w : result.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", w.getId());
            row.put("userId", w.getUserId());
            row.put("threadId", w.getThreadId());
            row.put("status", w.getStatus());
            row.put("modelCalls", w.getModelCalls());
            row.put("toolCalls", w.getToolCalls());
            row.put("totalTokens", w.getTotalTokens());
            row.put("createTime", w.getCreateTime());
            rows.add(row);
        }
        AdminDataDTOs.PageResult<Map<String, Object>> pr =
                new AdminDataDTOs.PageResult<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
        return Result.success(pr);
    }
}
