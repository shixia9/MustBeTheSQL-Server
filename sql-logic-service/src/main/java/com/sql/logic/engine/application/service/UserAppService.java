package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sql.logic.engine.domain.agent.core.AiAgentManager;
import com.sql.logic.engine.domain.agent.core.AiAgentWarmupRunner;
import com.sql.logic.engine.infrastructure.dao.UserInfoDao;
import com.sql.logic.engine.infrastructure.dao.UserLlmApiKeyDao;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import com.sql.logic.engine.infrastructure.po.UserLlmApiKey;
import com.sql.logic.engine.common.util.PasswordUtil;
import com.sql.logic.engine.common.util.UrlValidationUtil;
import com.sql.logic.engine.application.service.storage.StorageService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;

@Service
public class UserAppService {

    private final UserInfoDao userInfoDao;
    private final UserLlmApiKeyDao userLlmApiKeyDao;
    private final StorageService storageService;
    private final AiAgentWarmupRunner aiAgentWarmupRunner;
    private final AiAgentManager aiAgentManager;

    public UserAppService(UserInfoDao userInfoDao, 
                          UserLlmApiKeyDao userLlmApiKeyDao, 
                          StorageService storageService,
                          @Lazy AiAgentWarmupRunner aiAgentWarmupRunner,
                          AiAgentManager aiAgentManager) {
        this.userInfoDao = userInfoDao;
        this.userLlmApiKeyDao = userLlmApiKeyDao;
        this.storageService = storageService;
        this.aiAgentWarmupRunner = aiAgentWarmupRunner;
        this.aiAgentManager = aiAgentManager;
    }

    public UserInfo login(String email, String password) {
        if (email == null || password == null) {
            throw new IllegalArgumentException("Email and password cannot be null");
        }

        QueryWrapper<UserInfo> query = new QueryWrapper<>();
        query.eq("email", email);
        UserInfo user = userInfoDao.selectOne(query);

        if (user == null) {
            throw new IllegalArgumentException("Invalid email or password");
        }
        if (user.getStatus() != 1) {
            throw new IllegalStateException("User account is not active. Status: " + user.getStatus());
        }

        // Verify password using BCrypt (supports legacy plaintext for migration)
        if (!PasswordUtil.verify(password, user.getPassword())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        // Migrate plaintext password to BCrypt hash on successful login
        if (!PasswordUtil.isBcryptHash(user.getPassword())) {
            user.setPassword(PasswordUtil.hash(password));
            userInfoDao.updateById(user);
        }
        
        QueryWrapper<UserLlmApiKey> keyQuery = new QueryWrapper<>();
        keyQuery.eq("user_id", user.getId()).eq("status", 1);
        UserLlmApiKey userKey = userLlmApiKeyDao.selectOne(keyQuery);
        if (userKey != null) {
            user.setApiKey(userKey.getApiKey());
            user.setBaseUrl(userKey.getBaseUrl());
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
        user.setPassword(PasswordUtil.hash(password));
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
        
        QueryWrapper<UserLlmApiKey> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("status", 1);
        UserLlmApiKey userKey = userLlmApiKeyDao.selectOne(query);
        if (userKey != null) {
            user.setApiKey(userKey.getApiKey());
            user.setBaseUrl(userKey.getBaseUrl());
        }
        
        return user;
    }

    public void deductTokens(Long userId, int tokens) {
        if (tokens <= 0) return;

        // Custom keys check: do not deduct system tokens
        QueryWrapper<UserLlmApiKey> keyQuery = new QueryWrapper<>();
        keyQuery.eq("user_id", userId).eq("status", 1);
        UserLlmApiKey userKey = userLlmApiKeyDao.selectOne(keyQuery);

        if (userKey != null && userKey.getApiKey() != null && !userKey.getApiKey().trim().isEmpty()) {
            return;
        }

        // Atomic deduct: GREATEST ensures token_quota never goes negative,
        // and the WHERE clause ensures we only deduct if the user has enough quota.
        // This eliminates the race condition of the previous check-then-update approach.
        UpdateWrapper<UserInfo> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userId)
                .gt("token_quota", 0)
                .setSql("token_quota = GREATEST(token_quota - " + tokens + ", 0)");

        int updated = userInfoDao.update(null, updateWrapper);
        if (updated == 0) {
            // No rows updated means either user doesn't exist or token_quota was already 0
            // This is logged but not treated as an error — the generation was still consumed
            System.err.println("Audit Log: User " + userId + " token deduction skipped (quota depleted or user not found).");
        }
    }

    public void checkBeforeGeneration(Long userId) {
        UserInfo user = getUserById(userId);
        if (user.getStatus() != 1) {
            throw new IllegalStateException("User account is not active. Status: " + user.getStatus());
        }
        
        QueryWrapper<UserLlmApiKey> keyQuery = new QueryWrapper<>();
        keyQuery.eq("user_id", userId).eq("status", 1);
        UserLlmApiKey userKey = userLlmApiKeyDao.selectOne(keyQuery);
        
        if (userKey != null && userKey.getApiKey() != null && !userKey.getApiKey().trim().isEmpty()) {
            return;
        }
        
        if (user.getTokenQuota() == null || user.getTokenQuota() <= 0) {
            throw new IllegalStateException("Insufficient AI token quota. Please recharge or configure your own API keys.");
        }
    }

    public void updateKeys(Long userId, String apiKey, String baseUrl) {
        // SSRF prevention: validate baseURL points to an allowed external host
        UrlValidationUtil.validateBaseUrl(baseUrl);

        QueryWrapper<UserLlmApiKey> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        UserLlmApiKey userKey = userLlmApiKeyDao.selectOne(query);
        
        if (apiKey == null || apiKey.trim().isEmpty()) {
            // Remove custom key
            if (userKey != null) {
                userKey.setStatus(0);
                userKey.setUpdateTime(new Date());
                userLlmApiKeyDao.updateById(userKey);
                // Remove agent from manager
                aiAgentManager.removeAgent(userId);
            }
        } else {
            // Set custom key
            if (userKey == null) {
                userKey = new UserLlmApiKey();
                userKey.setUserId(userId);
                userKey.setStrategyName("openAiStrategy");
                userKey.setApiKey(apiKey);
                userKey.setBaseUrl(baseUrl);
                userKey.setStatus(1);
                userKey.setCreateTime(new Date());
                userKey.setUpdateTime(new Date());
                userLlmApiKeyDao.insert(userKey);
            } else {
                userKey.setApiKey(apiKey);
                userKey.setBaseUrl(baseUrl);
                userKey.setStatus(1);
                userKey.setUpdateTime(new Date());
                userLlmApiKeyDao.updateById(userKey);
            }
            // Trigger dynamic assembly
            try {
                aiAgentWarmupRunner.assembleAndRegister(userId, apiKey, baseUrl);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to assemble AI Agent with the provided API Key: " + e.getMessage());
            }
        }
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
        if (!PasswordUtil.verify(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("Incorrect old password");
        }
        user.setPassword(PasswordUtil.hash(newPassword));
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
