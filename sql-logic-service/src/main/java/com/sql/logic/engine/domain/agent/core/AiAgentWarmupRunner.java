package com.sql.logic.engine.domain.agent.core;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.application.service.DatabaseMetaDataService;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.strategy.ProviderType;
import com.sql.logic.engine.infrastructure.dao.UserLlmConfigDao;
import com.sql.logic.engine.infrastructure.llm.OpenAILLMStrategy;
import com.sql.logic.engine.infrastructure.po.UserLlmConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class AiAgentWarmupRunner implements ApplicationRunner {

    private final AiAgentManager aiAgentManager;
    private final AiAgentFactory aiAgentFactory;
    private final LlmClientManager llmClientManager;
    private final ChatClient.Builder defaultChatClientBuilder;
    private final UserLlmConfigDao userLlmConfigDao;
    private final DatabaseMetaDataService databaseMetaDataService;

    public AiAgentWarmupRunner(AiAgentManager aiAgentManager,
                               AiAgentFactory aiAgentFactory,
                               LlmClientManager llmClientManager,
                               ChatClient.Builder defaultChatClientBuilder,
                               UserLlmConfigDao userLlmConfigDao,
                               DatabaseMetaDataService databaseMetaDataService) {
        this.aiAgentManager = aiAgentManager;
        this.aiAgentFactory = aiAgentFactory;
        this.llmClientManager = llmClientManager;
        this.defaultChatClientBuilder = defaultChatClientBuilder;
        this.userLlmConfigDao = userLlmConfigDao;
        this.databaseMetaDataService = databaseMetaDataService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        System.out.println("[AiAgentWarmupRunner] Starting AI Agent Warmup process...");

        // 1. Create system default LLM strategy and register it
        LLMStrategy defaultStrategy = new OpenAILLMStrategy(defaultChatClientBuilder);
        llmClientManager.registerClient(0L, defaultStrategy);

        // 2. Create system default agent (userId=0) using the default strategy
        AiAgent defaultAgent = aiAgentFactory.createDefaultAgent(defaultChatClientBuilder);
        defaultAgent.setSchemaContextProvider(this::buildDynamicSchemaContext);
        aiAgentManager.registerAgent(0L, defaultAgent);

        // 3. Load all active user LLM configs and create pooled LLM strategies
        QueryWrapper<UserLlmConfig> query = new QueryWrapper<>();
        query.eq("status", 1);
        List<UserLlmConfig> configs = userLlmConfigDao.selectList(query);

        Set<Long> usersWithConfigs = new HashSet<>();

        if (configs != null && !configs.isEmpty()) {
            for (UserLlmConfig config : configs) {
                try {
                    ProviderType providerType = ProviderType.fromString(config.getProviderType());
                    LLMStrategy strategy = aiAgentFactory.createLLMStrategy(
                            providerType, config.getApiKey(), config.getBaseUrl(), config.getModelName());
                    llmClientManager.registerClient(config.getId(), strategy);
                    usersWithConfigs.add(config.getUserId());
                } catch (Exception e) {
                    System.err.println("[AiAgentWarmupRunner] Failed to create LLM client for config " + config.getId() + ": " + e.getMessage());
                }
            }
        }

        // 4. Create one agent per user who has at least one config
        for (Long userId : usersWithConfigs) {
            AiAgent agent = new SqlAiAgentImpl(defaultStrategy);
            agent.setSchemaContextProvider(this::buildDynamicSchemaContext);
            aiAgentManager.registerAgent(userId, agent);
        }

        System.out.println("[AiAgentWarmupRunner] Warmup completed. Agents: " + (1 + usersWithConfigs.size())
                + ", LLM clients: " + (1 + (configs != null ? configs.size() : 0)));
    }

    /**
     * Create and register an LLM strategy for a new/updated config at runtime.
     */
    public void assembleAndRegisterClient(Long configId, ProviderType providerType,
                                          String apiKey, String baseUrl, String modelName) {
        LLMStrategy strategy = aiAgentFactory.createLLMStrategy(providerType, apiKey, baseUrl, modelName);
        llmClientManager.registerClient(configId, strategy);
    }

    /**
     * Ensure an agent exists for a user (idempotent — only creates if not present).
     */
    public void ensureAgentForUser(Long userId) {
        if (aiAgentManager.getAgent(userId) == null || userId == 0L) {
            // Get the default strategy as fallback
            LLMStrategy defaultStrategy = llmClientManager.getClient(0L);
            AiAgent agent = new SqlAiAgentImpl(defaultStrategy);
            agent.setSchemaContextProvider(this::buildDynamicSchemaContext);
            aiAgentManager.registerAgent(userId, agent);
        }
    }

    private String buildDynamicSchemaContext(Long connectionId, List<String> tableNames) {
        if (connectionId == null || tableNames == null || tableNames.isEmpty()) {
            return "No specific database schema provided.";
        }
        StringBuilder sb = new StringBuilder("Database Schema Context:\n");
        for (String tableName : tableNames) {
            try {
                String ddl = databaseMetaDataService.getTableDDL(connectionId, tableName);
                if (ddl != null && !ddl.isEmpty()) {
                    sb.append(ddl).append("\n\n");
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch DDL for table: " + tableName);
            }
        }
        return sb.toString();
    }
}