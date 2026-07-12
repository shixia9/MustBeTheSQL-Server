package com.sql.logic.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.admin.dao.AdminUserDao;
import com.sql.logic.admin.po.AdminUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);
    private final AdminUserDao adminUserDao;

    public AdminUserService(AdminUserDao adminUserDao) {
        this.adminUserDao = adminUserDao;
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
    }

    public void removeAdmin(Long userId) {
        adminUserDao.delete(new QueryWrapper<AdminUser>().eq("user_id", userId));
    }

    public List<AdminUser> listAdmins() {
        return adminUserDao.selectList(new QueryWrapper<AdminUser>().eq("status", 1));
    }
}
