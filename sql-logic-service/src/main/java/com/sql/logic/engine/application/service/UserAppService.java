package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sql.logic.engine.domain.agent.core.AiAgentWarmupRunner;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.ProviderType;
import com.sql.logic.engine.infrastructure.dao.UserInfoDao;
import com.sql.logic.engine.infrastructure.dao.UserLlmConfigDao;
import com.sql.logic.engine.infrastructure.po.UserInfo;
import com.sql.logic.engine.infrastructure.po.UserLlmConfig;
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
    private final UserLlmConfigDao userLlmConfigDao;
    private final StorageService storageService;
    private final AiAgentWarmupRunner aiAgentWarmupRunner;
    private final LlmClientManager llmClientManager;

    public UserAppService(UserInfoDao userInfoDao,
                          UserLlmConfigDao userLlmConfigDao,
                          StorageService storageService,
                          @Lazy AiAgentWarmupRunner aiAgentWarmupRunner,
                          LlmClientManager llmClientManager) {
        this.userInfoDao = userInfoDao;
        this.userLlmConfigDao = userLlmConfigDao;
        this.storageService = storageService;
        this.aiAgentWarmupRunner = aiAgentWarmupRunner;
        this.llmClientManager = llmClientManager;
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

        // Populate deprecated apiKey/baseUrl fields from default config for backward compat
        populateLegacyKeyFields(user);

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
        user.setStatus(1);
        user.setTokenQuota(100);
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

        populateLegacyKeyFields(user);
        return user;
    }

    public UserInfo getUserByEmail(String email) {
        QueryWrapper<UserInfo> qw = new QueryWrapper<>();
        qw.eq("email", email);
        UserInfo user = userInfoDao.selectOne(qw);
        if (user == null) {
            throw new IllegalArgumentException("User not found with email: " + email);
        }
        return user;
    }

    public void deductTokens(Long userId, int tokens) {
        if (tokens <= 0) return;

        // Custom keys check: users with active LLM configs bypass system token deduction
        if (llmClientManager.hasActiveConfig(userId)) {
            return;
        }

        // Atomic deduct
        UpdateWrapper<UserInfo> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", userId)
                .gt("token_quota", 0)
                .setSql("token_quota = GREATEST(token_quota - " + tokens + ", 0)");

        int updated = userInfoDao.update(null, updateWrapper);
        if (updated == 0) {
            System.err.println("Audit Log: User " + userId + " token deduction skipped (quota depleted or user not found).");
        }
    }

    public void checkBeforeGeneration(Long userId) {
        UserInfo user = getUserById(userId);
        if (user.getStatus() != 1) {
            throw new IllegalStateException("User account is not active. Status: " + user.getStatus());
        }

        // Users with custom LLM configs bypass the quota check
        if (llmClientManager.hasActiveConfig(userId)) {
            return;
        }

        if (user.getTokenQuota() == null || user.getTokenQuota() <= 0) {
            throw new IllegalStateException("Insufficient AI token quota. Please recharge or configure your own API keys.");
        }
    }

    /**
     * @deprecated Use LlmConfigAppService.createConfig() instead.
     * Kept for backward compatibility with old frontend.
     */
    @Deprecated
    public void updateKeys(Long userId, String apiKey, String baseUrl) {
        UrlValidationUtil.validateBaseUrl(baseUrl);

        // Migrate to new config table: create/update a default OPENAI_COMPATIBLE config
        QueryWrapper<UserLlmConfig> configQuery = new QueryWrapper<>();
        configQuery.eq("user_id", userId).eq("is_default", 1).eq("status", 1);
        UserLlmConfig existingConfig = userLlmConfigDao.selectOne(configQuery);

        if (apiKey == null || apiKey.trim().isEmpty()) {
            // Remove custom key — deactivate all configs for this user
            if (existingConfig != null) {
                existingConfig.setStatus(0);
                existingConfig.setUpdateTime(new Date());
                userLlmConfigDao.updateById(existingConfig);
            }
            llmClientManager.removeClientsForUser(userId);
        } else {
            if (existingConfig == null) {
                existingConfig = new UserLlmConfig();
                existingConfig.setUserId(userId);
                existingConfig.setConfigName("My API Key");
                existingConfig.setProviderType(ProviderType.OPENAI_COMPATIBLE.name());
                existingConfig.setBaseUrl(baseUrl);
                existingConfig.setApiKey(apiKey);
                existingConfig.setModelName("gpt-4o");
                existingConfig.setIsDefault(1);
                existingConfig.setStatus(1);
                existingConfig.setCreateTime(new Date());
                existingConfig.setUpdateTime(new Date());
                userLlmConfigDao.insert(existingConfig);
            } else {
                existingConfig.setApiKey(apiKey);
                existingConfig.setBaseUrl(baseUrl);
                existingConfig.setUpdateTime(new Date());
                userLlmConfigDao.updateById(existingConfig);
            }

            // Create and register LLM client + ensure agent exists
            try {
                aiAgentWarmupRunner.assembleAndRegisterClient(existingConfig.getId(),
                        ProviderType.OPENAI_COMPATIBLE, apiKey, baseUrl, existingConfig.getModelName());
                aiAgentWarmupRunner.ensureAgentForUser(userId);
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
        user.setStatus(-1);
        user.setUpdateTime(new Date());
        userInfoDao.updateById(user);
    }

    private void populateLegacyKeyFields(UserInfo user) {
        QueryWrapper<UserLlmConfig> query = new QueryWrapper<>();
        query.eq("user_id", user.getId()).eq("status", 1).eq("is_default", 1);
        UserLlmConfig config = userLlmConfigDao.selectOne(query);
        if (config != null) {
            user.setApiKey(config.getApiKey());
            user.setBaseUrl(config.getBaseUrl());
        }
    }
}