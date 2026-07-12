package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.infrastructure.dao.AdminUserDao;
import com.sql.logic.engine.infrastructure.dao.UserInfoDao;
import com.sql.logic.engine.infrastructure.po.AdminUser;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AdminUserAppService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserAppService.class);

    private final AdminUserDao adminUserDao;
    private final UserInfoDao userInfoDao;

    public AdminUserAppService(AdminUserDao adminUserDao, UserInfoDao userInfoDao) {
        this.adminUserDao = adminUserDao;
        this.userInfoDao = userInfoDao;
    }

    public boolean isSystemAdmin(Long userId) {
        if (userId == null) return false;
        return adminUserDao.findByUserId(userId) != null;
    }

    public boolean isSuperAdmin(Long userId) {
        AdminUser a = adminUserDao.findByUserId(userId);
        return a != null && a.isSuperAdmin();
    }

    public AdminUser getAdmin(Long userId) {
        return adminUserDao.findByUserId(userId);
    }

    /** Paginated user list for admin. */
    public Page<Map<String, Object>> listUsers(int page, int size, String keyword, String status) {
        Page<UserInfo> p = new Page<>(page, size);
        QueryWrapper<UserInfo> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("username", keyword.trim()).or().like("email", keyword.trim()));
        }
        if (status != null && !status.isBlank()) {
            qw.eq("status", Integer.parseInt(status));
        }
        qw.orderByDesc("id");
        Page<UserInfo> userPage = userInfoDao.selectPage(p, qw);

        Page<Map<String, Object>> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UserInfo u : userPage.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", u.getId());
            row.put("username", u.getUsername());
            row.put("email", u.getEmail());
            row.put("status", u.getStatus());
            row.put("tokenQuota", u.getTokenQuota());
            row.put("createTime", u.getCreateTime());
            AdminUser au = adminUserDao.findByUserId(u.getId());
            row.put("isAdmin", au != null);
            row.put("adminRole", au != null ? au.getRole() : null);
            rows.add(row);
        }
        result.setRecords(rows);
        return result;
    }

    /** Toggle user status. */
    public void toggleUserStatus(Long userId, int newStatus) {
        UserInfo u = new UserInfo();
        u.setId(userId);
        u.setStatus(newStatus);
        userInfoDao.updateById(u);
        log.info("[AdminUserAppService] User {} status toggled to {}", userId, newStatus);
    }

    /** Adjust user token quota. */
    public void adjustQuota(Long userId, long quota) {
        UserInfo u = new UserInfo();
        u.setId(userId);
        u.setTokenQuota((int) quota);
        userInfoDao.updateById(u);
        log.info("[AdminUserAppService] User {} quota adjusted to {}", userId, quota);
    }

    /** Add an admin user (SUPER_ADMIN only). */
    public void addAdmin(Long userId, String role, Long createdBy) {
        AdminUser existing = adminUserDao.findByUserId(userId);
        if (existing != null) {
            existing.setRole(role);
            existing.setStatus(1);
            adminUserDao.updateById(existing);
        } else {
            AdminUser a = new AdminUser();
            a.setUserId(userId);
            a.setRole(role);
            a.setStatus(1);
            a.setCreatedBy(createdBy);
            a.setCreateTime(new Date());
            adminUserDao.insert(a);
        }
        log.info("[AdminUserAppService] Added admin userId={} role={}", userId, role);
    }

    /** Remove an admin user (SUPER_ADMIN only). */
    public void removeAdmin(Long userId) {
        adminUserDao.delete(new QueryWrapper<AdminUser>().eq("user_id", userId));
        log.info("[AdminUserAppService] Removed admin userId={}", userId);
    }

    /** List all admin users. */
    public List<AdminUser> listAdmins() {
        return adminUserDao.selectList(new QueryWrapper<AdminUser>().eq("status", 1));
    }
}
