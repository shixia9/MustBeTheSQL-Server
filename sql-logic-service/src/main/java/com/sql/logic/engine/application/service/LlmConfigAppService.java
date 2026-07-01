package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.sql.logic.engine.common.dto.LlmConfigCreateRequest;
import com.sql.logic.engine.common.dto.LlmConfigResponse;
import com.sql.logic.engine.common.dto.LlmConfigUpdateRequest;
import com.sql.logic.engine.common.util.UrlValidationUtil;
import com.sql.logic.engine.domain.agent.core.AiAgentManager;
import com.sql.logic.engine.domain.agent.core.AiAgentWarmupRunner;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.ProviderType;
import com.sql.logic.engine.infrastructure.dao.UserLlmConfigDao;
import com.sql.logic.engine.infrastructure.po.UserLlmConfig;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Service
public class LlmConfigAppService {

    private final UserLlmConfigDao userLlmConfigDao;
    private final AiAgentWarmupRunner aiAgentWarmupRunner;
    private final AiAgentManager aiAgentManager;
    private final LlmClientManager llmClientManager;

    public LlmConfigAppService(UserLlmConfigDao userLlmConfigDao,
                               AiAgentWarmupRunner aiAgentWarmupRunner,
                               AiAgentManager aiAgentManager,
                               LlmClientManager llmClientManager) {
        this.userLlmConfigDao = userLlmConfigDao;
        this.aiAgentWarmupRunner = aiAgentWarmupRunner;
        this.aiAgentManager = aiAgentManager;
        this.llmClientManager = llmClientManager;
    }

    /**
     * List all active configs for a user, with masked API keys.
     */
    public List<LlmConfigResponse> listConfigs(Long userId) {
        QueryWrapper<UserLlmConfig> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("status", 1).orderByDesc("is_default").orderByAsc("create_time");
        List<UserLlmConfig> configs = userLlmConfigDao.selectList(query);
        return configs.stream().map(this::toResponse).toList();
    }

    /**
     * Create a new LLM config for a user.
     */
    public LlmConfigResponse createConfig(Long userId, LlmConfigCreateRequest request) {
        if (request.getConfigName() == null || request.getConfigName().isBlank()) {
            throw new IllegalArgumentException("Config name is required");
        }
        if (request.getApiKey() == null || request.getApiKey().isBlank()) {
            throw new IllegalArgumentException("API key is required");
        }

        // SSRF prevention
        if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
            UrlValidationUtil.validateBaseUrl(request.getBaseUrl());
        }

        ProviderType providerType = ProviderType.fromString(request.getProviderType());

        // Handle isDefault
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            clearDefaultForUser(userId);
        }

        // If this is the first config for the user, make it default automatically
        QueryWrapper<UserLlmConfig> countQuery = new QueryWrapper<>();
        countQuery.eq("user_id", userId).eq("status", 1);
        long existingCount = userLlmConfigDao.selectCount(countQuery);
        boolean shouldBeDefault = Boolean.TRUE.equals(request.getIsDefault()) || existingCount == 0;

        UserLlmConfig config = new UserLlmConfig();
        config.setUserId(userId);
        config.setConfigName(request.getConfigName());
        config.setProviderType(providerType.name());
        config.setBaseUrl(request.getBaseUrl());
        config.setApiKey(request.getApiKey());
        config.setModelName(request.getModelName());
        config.setIsDefault(shouldBeDefault ? 1 : 0);
        config.setStatus(1);
        config.setCreateTime(new Date());
        config.setUpdateTime(new Date());
        userLlmConfigDao.insert(config);

        // Create and register the LLM client in the pool
        try {
            aiAgentWarmupRunner.assembleAndRegisterClient(config.getId(), providerType,
                    request.getApiKey(), request.getBaseUrl(), request.getModelName());
        } catch (Exception e) {
            System.err.println("[LlmConfigAppService] Failed to create LLM client for config " + config.getId() + ": " + e.getMessage());
            // Don't fail the create — the config is saved, client will be created on restart
        }

        // Ensure the user has an agent
        aiAgentWarmupRunner.ensureAgentForUser(userId);

        return toResponse(config);
    }

    /**
     * Update an existing LLM config.
     */
    public LlmConfigResponse updateConfig(Long userId, LlmConfigUpdateRequest request) {
        if (request.getConfigId() == null) {
            throw new IllegalArgumentException("Config ID is required");
        }

        UserLlmConfig config = userLlmConfigDao.selectById(request.getConfigId());
        if (config == null || !config.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Config not found or does not belong to this user");
        }

        // SSRF prevention for new base URL
        if (request.getBaseUrl() != null && !request.getBaseUrl().isBlank()) {
            UrlValidationUtil.validateBaseUrl(request.getBaseUrl());
        }

        boolean needClientRebuild = false;
        String newApiKey = config.getApiKey();
        String newBaseUrl = config.getBaseUrl();
        String newModelName = config.getModelName();
        ProviderType newProviderType = ProviderType.fromString(config.getProviderType());

        if (request.getConfigName() != null) {
            config.setConfigName(request.getConfigName());
        }
        if (request.getProviderType() != null) {
            newProviderType = ProviderType.fromString(request.getProviderType());
            config.setProviderType(newProviderType.name());
            needClientRebuild = true;
        }
        if (request.getBaseUrl() != null) {
            newBaseUrl = request.getBaseUrl();
            config.setBaseUrl(request.getBaseUrl());
            needClientRebuild = true;
        }
        if (request.getApiKey() != null) {
            newApiKey = request.getApiKey();
            config.setApiKey(request.getApiKey());
            needClientRebuild = true;
        }
        if (request.getModelName() != null) {
            newModelName = request.getModelName();
            config.setModelName(request.getModelName());
            needClientRebuild = true;
        }
        if (request.getIsDefault() != null && request.getIsDefault()) {
            clearDefaultForUser(userId);
            config.setIsDefault(1);
        }
        if (request.getStatus() != null) {
            config.setStatus(request.getStatus());
        }

        config.setUpdateTime(new Date());
        userLlmConfigDao.updateById(config);

        // Rebuild LLM client if needed
        if (needClientRebuild && config.getStatus() == 1) {
            try {
                aiAgentWarmupRunner.assembleAndRegisterClient(config.getId(), newProviderType,
                        newApiKey, newBaseUrl, newModelName);
            } catch (Exception e) {
                System.err.println("[LlmConfigAppService] Failed to rebuild LLM client for config " + config.getId() + ": " + e.getMessage());
            }
        } else if (config.getStatus() == 0) {
            // Config deactivated — remove from client pool
            llmClientManager.removeClient(config.getId());
        }

        return toResponse(config);
    }

    /**
     * Soft-delete a config (set status = 0).
     */
    public void deleteConfig(Long userId, Long configId) {
        UserLlmConfig config = userLlmConfigDao.selectById(configId);
        if (config == null || !config.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Config not found or does not belong to this user");
        }

        config.setStatus(0);
        config.setUpdateTime(new Date());
        userLlmConfigDao.updateById(config);

        // Remove from client pool
        llmClientManager.removeClient(configId);

        // If this was the default config, promote another active config as default
        if (config.getIsDefault() == 1) {
            QueryWrapper<UserLlmConfig> promoteQuery = new QueryWrapper<>();
            promoteQuery.eq("user_id", userId).eq("status", 1).orderByAsc("create_time").last("LIMIT 1");
            UserLlmConfig nextDefault = userLlmConfigDao.selectOne(promoteQuery);
            if (nextDefault != null) {
                nextDefault.setIsDefault(1);
                nextDefault.setUpdateTime(new Date());
                userLlmConfigDao.updateById(nextDefault);
            }
        }
    }

    /**
     * Set a config as the user's default.
     */
    public void setDefaultConfig(Long userId, Long configId) {
        UserLlmConfig config = userLlmConfigDao.selectById(configId);
        if (config == null || !config.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Config not found or does not belong to this user");
        }
        if (config.getStatus() != 1) {
            throw new IllegalArgumentException("Cannot set an inactive config as default");
        }

        clearDefaultForUser(userId);

        config.setIsDefault(1);
        config.setUpdateTime(new Date());
        userLlmConfigDao.updateById(config);
    }

    /**
     * Get a single config by ID (for internal use).
     */
    public UserLlmConfig getConfigById(Long configId) {
        return userLlmConfigDao.selectById(configId);
    }

    /**
     * Check if a user has any active custom LLM config.
     */
    public boolean hasActiveConfig(Long userId) {
        return llmClientManager.hasActiveConfig(userId);
    }

    /**
     * test connectivity to an LLM provider config by sending a minimal
     * chat completion and measuring round-trip latency. Best-effort: any failure
     * returns success=false with the underlying error message.
     */
    public java.util.Map<String, Object> testConnection(Long userId, Long configId) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        UserLlmConfig config = userLlmConfigDao.selectById(configId);
        if (config == null || !config.getUserId().equals(userId) || config.getStatus() != 1) {
            result.put("success", false);
            result.put("latencyMs", 0);
            result.put("message", "Config not found or inactive");
            return result;
        }
        long start = System.currentTimeMillis();
        try {
            String ack = llmClientManager.testPing(configId, userId);
            long latency = System.currentTimeMillis() - start;
            result.put("success", true);
            result.put("latencyMs", latency);
            result.put("message", ack != null ? ack : "OK");
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            result.put("success", false);
            result.put("latencyMs", latency);
            result.put("message", e.getMessage() != null ? e.getMessage() : "Connection failed");
        }
        return result;
    }

    // ---- Private helpers ----

    private void clearDefaultForUser(Long userId) {
        UpdateWrapper<UserLlmConfig> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("user_id", userId)
                .eq("is_default", 1)
                .set("is_default", 0)
                .set("update_time", new Date());
        userLlmConfigDao.update(null, updateWrapper);
    }

    private LlmConfigResponse toResponse(UserLlmConfig config) {
        LlmConfigResponse response = new LlmConfigResponse();
        response.setId(config.getId());
        response.setConfigName(config.getConfigName());
        response.setProviderType(config.getProviderType());
        response.setBaseUrl(config.getBaseUrl());
        response.setApiKeyMasked(maskApiKey(config.getApiKey()));
        response.setModelName(config.getModelName());
        response.setIsDefault(config.getIsDefault() == 1);
        response.setStatus(config.getStatus());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        response.setCreateTime(config.getCreateTime() != null ? sdf.format(config.getCreateTime()) : null);
        response.setUpdateTime(config.getUpdateTime() != null ? sdf.format(config.getUpdateTime()) : null);
        return response;
    }

    private String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 3) + "***...***" + apiKey.substring(apiKey.length() - 3);
    }
}