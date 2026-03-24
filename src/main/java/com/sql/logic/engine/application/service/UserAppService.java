package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.infrastructure.dao.UserInfoDao;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class UserAppService {

    private final UserInfoDao userInfoDao;

    public UserAppService(UserInfoDao userInfoDao) {
        this.userInfoDao = userInfoDao;
    }

    public UserInfo login(String email, String password) {
        if (email == null || password == null) {
            throw new IllegalArgumentException("Email and password cannot be null");
        }
        
        QueryWrapper<UserInfo> query = new QueryWrapper<>();
        query.eq("email", email).eq("password", password);
        UserInfo user = userInfoDao.selectOne(query);
        
        if (user == null) {
            throw new IllegalArgumentException("Invalid email or password");
        }
        if (user.getStatus() != 1) {
            throw new IllegalStateException("User account is not active. Status: " + user.getStatus());
        }
        return user;
    }

    public UserInfo register(String username, String password, String email) {
        QueryWrapper<UserInfo> usernameQuery = new QueryWrapper<>();
        usernameQuery.eq("username", username);
        if (userInfoDao.selectCount(usernameQuery) > 0) {
            throw new IllegalArgumentException("Username already exists");
        }

        QueryWrapper<UserInfo> emailQuery = new QueryWrapper<>();
        emailQuery.eq("email", email);
        if (userInfoDao.selectCount(emailQuery) > 0) {
            throw new IllegalArgumentException("Email already exists");
        }

        UserInfo user = new UserInfo();
        user.setUsername(username);
        user.setPassword(password); // In production, this should be hashed
        user.setEmail(email);
        user.setStatus(1); // 1 = Active
        user.setTokenQuota(100); // Default 100 tokens
        user.setCreateTime(new Date());
        user.setUpdateTime(new Date());
        
        userInfoDao.insert(user);
        return user;
    }

    public void checkAndDeductToken(Long userId) {
        UserInfo user = userInfoDao.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        if (user.getStatus() != 1) {
            throw new IllegalStateException("User account is not active. Status: " + user.getStatus());
        }
        if (user.getTokenQuota() <= 0) {
            throw new IllegalStateException("Insufficient AI token quota. Please recharge or contact admin.");
        }
        
        // Deduct 1 token per request
        user.setTokenQuota(user.getTokenQuota() - 1);
        userInfoDao.updateById(user);
    }
}
