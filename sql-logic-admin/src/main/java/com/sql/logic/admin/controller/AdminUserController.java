package com.sql.logic.admin.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.sql.logic.admin.service.AdminUserService;
import com.sql.logic.engine.common.dubbo.AdminDataDTOs;
import com.sql.logic.engine.common.dubbo.AdminDataService;
import com.sql.logic.engine.common.response.Result;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    @DubboReference
    private AdminDataService adminDataService;

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping("/check")
    public Result<Map<String, Object>> checkAdmin() {
        Long userId = getCurrentUserId();
        boolean isAdmin = adminUserService.isSystemAdmin(userId);
        return Result.success(Map.of("isAdmin", isAdmin,
                "role", isAdmin ? adminUserService.getAdmin(userId).getRole() : "USER"));
    }

    @GetMapping("/users")
    public Result<AdminDataDTOs.PageResult<AdminDataDTOs.UserDTO>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return Result.success(adminDataService.listUsers(page, size, keyword, status));
    }

    @PutMapping("/users/{userId}/status")
    public Result<Void> toggleUserStatus(@PathVariable Long userId, @RequestBody Map<String, Integer> body) {
        adminDataService.toggleUserStatus(userId, body.get("status"));
        return Result.success(null);
    }

    @PutMapping("/users/{userId}/quota")
    public Result<Void> adjustQuota(@PathVariable Long userId, @RequestBody Map<String, Object> body) {
        long quota = ((Number) body.get("quota")).longValue();
        adminDataService.adjustQuota(userId, quota);
        return Result.success(null);
    }

    @GetMapping("/admins")
    public Result<?> listAdmins() {
        return Result.success(adminUserService.listAdmins());
    }

    @PostMapping("/admins")
    public Result<Void> addAdmin(@RequestBody Map<String, Object> body) {
        Long createdBy = getCurrentUserId();
        if (!adminUserService.isSuperAdmin(createdBy)) {
            return Result.error(403, "Only SUPER_ADMIN can manage admins");
        }
        Long userId = ((Number) body.get("userId")).longValue();
        String role = (String) body.getOrDefault("role", "ADMIN");
        adminUserService.addAdmin(userId, role, createdBy);
        return Result.success(null);
    }

    @DeleteMapping("/admins/{userId}")
    public Result<Void> removeAdmin(@PathVariable Long userId) {
        Long createdBy = getCurrentUserId();
        if (!adminUserService.isSuperAdmin(createdBy)) {
            return Result.error(403, "Only SUPER_ADMIN can manage admins");
        }
        adminUserService.removeAdmin(userId);
        return Result.success(null);
    }

    private Long getCurrentUserId() {
        return Long.valueOf((String) StpUtil.getLoginId());
    }
}
