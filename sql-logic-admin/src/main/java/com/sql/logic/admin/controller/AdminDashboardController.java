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
}
