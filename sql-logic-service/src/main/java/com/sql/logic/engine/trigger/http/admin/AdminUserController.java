package com.sql.logic.engine.trigger.http.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.application.service.AdminUserAppService;
import com.sql.logic.engine.common.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final AdminUserAppService adminUserAppService;

    public AdminUserController(AdminUserAppService adminUserAppService) {
        this.adminUserAppService = adminUserAppService;
    }

    /** Check if the current user is an admin (no AdminGuard on this endpoint). */
    @GetMapping("/check")
    public Result<Map<String, Object>> checkAdmin() {
        Long userId = getCurrentUserId();
        boolean isAdmin = adminUserAppService.isSystemAdmin(userId);
        return Result.success(Map.of("isAdmin", isAdmin,
                "role", isAdmin ? adminUserAppService.getAdmin(userId).getRole() : "USER"));
    }

    @GetMapping("/users")
    public Result<Page<Map<String, Object>>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return Result.success(adminUserAppService.listUsers(page, size, keyword, status));
    }

    @PutMapping("/users/{userId}/status")
    public Result<Void> toggleUserStatus(@PathVariable Long userId, @RequestBody Map<String, Integer> body) {
        adminUserAppService.toggleUserStatus(userId, body.get("status"));
        return Result.success(null);
    }

    @PutMapping("/users/{userId}/quota")
    public Result<Void> adjustQuota(@PathVariable Long userId, @RequestBody Map<String, Object> body) {
        long quota = ((Number) body.get("quota")).longValue();
        adminUserAppService.adjustQuota(userId, quota);
        return Result.success(null);
    }

    @GetMapping("/admins")
    public Result<?> listAdmins() {
        return Result.success(adminUserAppService.listAdmins());
    }

    @PostMapping("/admins")
    public Result<Void> addAdmin(@RequestBody Map<String, Object> body) {
        // Requires SUPER_ADMIN — checked inline
        Long createdBy = getCurrentUserId();
        if (!adminUserAppService.isSuperAdmin(createdBy)) {
            return Result.error(403, "Only SUPER_ADMIN can manage admins");
        }
        Long userId = ((Number) body.get("userId")).longValue();
        String role = (String) body.getOrDefault("role", "ADMIN");
        adminUserAppService.addAdmin(userId, role, createdBy);
        return Result.success(null);
    }

    @DeleteMapping("/admins/{userId}")
    public Result<Void> removeAdmin(@PathVariable Long userId) {
        Long createdBy = getCurrentUserId();
        if (!adminUserAppService.isSuperAdmin(createdBy)) {
            return Result.error(403, "Only SUPER_ADMIN can manage admins");
        }
        adminUserAppService.removeAdmin(userId);
        return Result.success(null);
    }

    private Long getCurrentUserId() {
        String idStr = (String) cn.dev33.satoken.stp.StpUtil.getLoginId();
        return Long.valueOf(idStr);
    }
}
