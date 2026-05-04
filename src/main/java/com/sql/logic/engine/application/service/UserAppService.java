package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sql.logic.engine.infrastructure.dao.UserInfoDao;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import com.sql.logic.engine.application.service.storage.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;

@Service
public class UserAppService {

    private final UserInfoDao userInfoDao;
    private final StorageService storageService;

    public UserAppService(UserInfoDao userInfoDao, StorageService storageService) {
        this.userInfoDao = userInfoDao;
        this.storageService = storageService;
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

    public UserInfo getUserById(Long userId) {
        UserInfo user = userInfoDao.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        return user;
    }

    public void deductTokens(Long userId, int tokens) {
        if (tokens <= 0) return;
        
        UserInfo user = getUserById(userId);
        
        // Custom keys check: do not deduct system tokens
        if (user.getApiKey() != null && !user.getApiKey().trim().isEmpty() 
            && user.getSecretKey() != null && !user.getSecretKey().trim().isEmpty()) {
            return;
        }
        
        UpdateWrapper<UserInfo> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userId)
                     .ge("token_quota", tokens)
                     .setSql("token_quota = token_quota - " + tokens);
                     
        int updated = userInfoDao.update(null, updateWrapper);
        if (updated == 0) {
            // Check if user still exists but has insufficient tokens to cover the exact amount
            UserInfo checkUser = userInfoDao.selectById(userId);
            if (checkUser != null && checkUser.getTokenQuota() < tokens) {
                // Set token_quota to 0 to prevent negative balance
                UpdateWrapper<UserInfo> zeroWrapper = new UpdateWrapper<>();
                zeroWrapper.eq("id", userId)
                           .set("token_quota", 0);
                userInfoDao.update(null, zeroWrapper);
                System.err.println("Audit Log: User " + userId + " consumed " + tokens + " tokens but only had " + checkUser.getTokenQuota() + ". Balance set to 0.");
            } else {
                System.err.println("Audit Log: Failed to deduct tokens for user " + userId + " due to missing record or concurrency conflict.");
            }
        }
    }

    public void checkBeforeGeneration(Long userId) {
        UserInfo user = getUserById(userId);
        if (user.getStatus() != 1) {
            throw new IllegalStateException("User account is not active. Status: " + user.getStatus());
        }
        
        if (user.getApiKey() != null && !user.getApiKey().trim().isEmpty() 
            && user.getSecretKey() != null && !user.getSecretKey().trim().isEmpty()) {
            return;
        }
        
        if (user.getTokenQuota() == null || user.getTokenQuota() <= 0) {
            throw new IllegalStateException("Insufficient AI token quota. Please recharge or configure your own API keys.");
        }
    }

    public void updateKeys(Long userId, String apiKey, String secretKey) {
        UserInfo user = new UserInfo();
        user.setId(userId);
        user.setApiKey(apiKey);
        user.setSecretKey(secretKey);
        user.setUpdateTime(new Date());
        userInfoDao.updateById(user);
    }

    public UserInfo updateProfile(Long userId, String username, String email) {
        UserInfo user = getUserById(userId);
        if (username != null && !username.equals(user.getUsername())) {
            QueryWrapper<UserInfo> usernameQuery = new QueryWrapper<>();
            usernameQuery.eq("username", username);
            if (userInfoDao.selectCount(usernameQuery) > 0) {
                throw new IllegalArgumentException("Username already exists");
            }
            user.setUsername(username);
        }
        if (email != null && !email.equals(user.getEmail())) {
            QueryWrapper<UserInfo> emailQuery = new QueryWrapper<>();
            emailQuery.eq("email", email);
            if (userInfoDao.selectCount(emailQuery) > 0) {
                throw new IllegalArgumentException("Email already exists");
            }
            user.setEmail(email);
        }
        user.setUpdateTime(new Date());
        userInfoDao.updateById(user);
        return user;
    }

    public void updatePassword(Long userId, String oldPassword, String newPassword) {
        UserInfo user = getUserById(userId);
        if (!user.getPassword().equals(oldPassword)) {
            throw new IllegalArgumentException("Incorrect old password");
        }
        user.setPassword(newPassword);
        user.setUpdateTime(new Date());
        userInfoDao.updateById(user);
    }

    public UserInfo updateAvatar(Long userId, MultipartFile file) {
        UserInfo user = getUserById(userId);
        String avatarUrl = storageService.store(file);
        user.setAvatar(avatarUrl);
        user.setUpdateTime(new Date());
        userInfoDao.updateById(user);
        return user;
    }

    public void cancelAccount(Long userId) {
        UserInfo user = new UserInfo();
        user.setId(userId);
        user.setStatus(-1); // Cancelled
        user.setUpdateTime(new Date());
        userInfoDao.updateById(user);
    }
}
