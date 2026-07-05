package com.sql.logic.engine.domain.agent.core;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.infrastructure.dao.UserLlmConfigDao;
import com.sql.logic.engine.infrastructure.po.UserLlmConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages LLMStrategy instances keyed by configId.
 *
 * Each UserLlmConfig corresponds to one LLMStrategy (which wraps a ChatClient
 * with its own connection pool and credentials). These are the true "heavy resources"
 * that need to be pooled and reused across requests.
 *
 * The system default strategy is stored under key 0L.
 */
@Component
public class LlmClientManager {

    private static final Logger log = LoggerFactory.getLogger(LlmClientManager.class);

    private final ConcurrentHashMap<Long, LLMStrategy> clientCache = new ConcurrentHashMap<>();
    private final UserLlmConfigDao userLlmConfigDao;

    public LlmClientManager(UserLlmConfigDao userLlmConfigDao) {
        this.userLlmConfigDao = userLlmConfigDao;
    }

    /**
     * Register a LLMStrategy for a given configId.
     */
    public void registerClient(Long configId, LLMStrategy strategy) {
        clientCache.put(configId, strategy);
        log.info("[LlmClientManager] Registered LLM client for config: {}", configId);
    }

    /**
     * Get a LLMStrategy by configId.
     * Falls back to system default (key 0) if the specific config is not found.
     */
    public LLMStrategy getClient(Long configId) {
        if (configId != null && configId > 0) {
            LLMStrategy strategy = clientCache.get(configId);
            if (strategy != null) {
                return strategy;
            }
        }
        // Fallback to system default
        return clientCache.get(0L);
    }

    /**
     * Resolve the best LLM strategy for a request.
     * <p>
     * Resolution order:
     * 1. If llmConfigId is explicitly provided and found, use it.
     * 2. If userId is provided, look up the user's default active config.
     * 3. Fall back to system default (key 0).
     * <p>
     * This matches the behavior of SQLGenerateAppService for non-agent flows.
     */
    public LLMStrategy resolveStrategy(Long llmConfigId, Long userId) {
        // Explicit system default
        if (llmConfigId != null && llmConfigId == 0) {
            return clientCache.get(0L);
        }
        // 1. Explicit config
        if (llmConfigId != null && llmConfigId > 0) {
            LLMStrategy strategy = clientCache.get(llmConfigId);
            if (strategy != null) {
                return strategy;
            }
        }
        // 2. User default
        if (userId != null && userId > 0) {
            LLMStrategy userDefault = getDefaultForUser(userId);
            if (userDefault != null) {
                return userDefault;
            }
        }
        // 3. System default
        return clientCache.get(0L);
    }

    /**
     * Get the default LLMStrategy for a user.
     * Looks up the user's isDefault config first, then falls back to any active config,
     * then to system default.
     */
    public LLMStrategy getDefaultForUser(Long userId) {
        // Try to find the user's default config
        QueryWrapper<UserLlmConfig> defaultQuery = new QueryWrapper<>();
        defaultQuery.eq("user_id", userId).eq("is_default", 1).eq("status", 1);
        UserLlmConfig defaultConfig = userLlmConfigDao.selectOne(defaultQuery);

        if (defaultConfig != null) {
            LLMStrategy strategy = clientCache.get(defaultConfig.getId());
            if (strategy != null) {
                return strategy;
            }
        }

        // Try any active config for this user
        QueryWrapper<UserLlmConfig> anyQuery = new QueryWrapper<>();
        anyQuery.eq("user_id", userId).eq("status", 1);
        List<UserLlmConfig> configs = userLlmConfigDao.selectList(anyQuery);
        for (UserLlmConfig config : configs) {
            LLMStrategy strategy = clientCache.get(config.getId());
            if (strategy != null) {
                return strategy;
            }
        }

        // System default
        return clientCache.get(0L);
    }

    /**
     * Remove a LLMStrategy by configId.
     */
    public void removeClient(Long configId) {
        clientCache.remove(configId);
        log.info("[LlmClientManager] Removed LLM client for config: {}", configId);
    }

    /**
     * Remove all LLMStrategy instances for a given user.
     */
    public void removeClientsForUser(Long userId) {
        QueryWrapper<UserLlmConfig> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        List<UserLlmConfig> configs = userLlmConfigDao.selectList(query);
        for (UserLlmConfig config : configs) {
            clientCache.remove(config.getId());
        }
        log.info("[LlmClientManager] Removed all LLM clients for user: {}", userId);
    }

    /**
     * Check if a user has any active custom LLM config.
     */
    public boolean hasActiveConfig(Long userId) {
        QueryWrapper<UserLlmConfig> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("status", 1);
        return userLlmConfigDao.selectCount(query) > 0;
    }

    /**
     * send a minimal non-streaming prompt to the resolved LLM strategy
     * to verify connectivity. Returns a short acknowledgement string on success
     * or throws on failure. Used by the LLM config "Test Connection" feature.
     */
    public String testPing(Long configId, Long userId) {
        LLMStrategy strategy = resolveStrategy(configId, userId);
        if (strategy == null) {
            throw new IllegalStateException("No LLM strategy available for config " + configId);
        }
        String result = strategy.generateSql("Reply with: OK", (tokens, content) -> { /* ignore token accounting */ });
        return result != null && !result.isBlank() ? result.trim() : "OK";
    }
}