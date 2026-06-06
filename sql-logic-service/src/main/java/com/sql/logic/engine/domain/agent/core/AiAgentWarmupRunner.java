package com.sql.logic.engine.domain.agent.core;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.application.service.DatabaseMetaDataService;
import com.sql.logic.engine.infrastructure.dao.UserLlmApiKeyDao;
import com.sql.logic.engine.infrastructure.po.UserLlmApiKey;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AiAgentWarmupRunner implements ApplicationRunner {

    private final AiAgentManager aiAgentManager;
    private final AiAgentFactory aiAgentFactory;
    private final ChatClient.Builder defaultChatClientBuilder;
    private final UserLlmApiKeyDao userLlmApiKeyDao;
    private final DatabaseMetaDataService databaseMetaDataService;

    public AiAgentWarmupRunner(AiAgentManager aiAgentManager, 
                               AiAgentFactory aiAgentFactory, 
                               ChatClient.Builder defaultChatClientBuilder,
                               UserLlmApiKeyDao userLlmApiKeyDao,
                               DatabaseMetaDataService databaseMetaDataService) {
        this.aiAgentManager = aiAgentManager;
        this.aiAgentFactory = aiAgentFactory;
        this.defaultChatClientBuilder = defaultChatClientBuilder;
        this.userLlmApiKeyDao = userLlmApiKeyDao;
        this.databaseMetaDataService = databaseMetaDataService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        System.out.println("[AiAgentWarmupRunner] Starting AI Agent Warmup process...");

        // 1. Assemble System Default Agent (User ID 0)
        AiAgent defaultAgent = aiAgentFactory.createDefaultAgent(defaultChatClientBuilder);
        defaultAgent.setSchemaContextProvider(this::buildDynamicSchemaContext);
        aiAgentManager.registerAgent(0L, defaultAgent);

        // 2. Load active custom API keys and assemble their dedicated Agents
        QueryWrapper<UserLlmApiKey> query = new QueryWrapper<>();
        query.eq("status", 1);
        List<UserLlmApiKey> customKeys = userLlmApiKeyDao.selectList(query);

        if (customKeys != null && !customKeys.isEmpty()) {
            for (UserLlmApiKey keyConf : customKeys) {
                try {
                    assembleAndRegister(keyConf.getUserId(), keyConf.getApiKey(), keyConf.getBaseUrl());
                } catch (Exception e) {
                    System.err.println("[AiAgentWarmupRunner] Failed to assemble agent for user " + keyConf.getUserId() + ": " + e.getMessage());
                }
            }
        }
        
        System.out.println("[AiAgentWarmupRunner] AI Agent Warmup process completed. Total agents assembled: " + (1 + (customKeys != null ? customKeys.size() : 0)));
    }

    public void assembleAndRegister(Long userId, String apiKey, String baseUrl) {
        AiAgent customAgent = aiAgentFactory.createOpenAiAgent(apiKey, baseUrl);
        customAgent.setSchemaContextProvider(this::buildDynamicSchemaContext);
        aiAgentManager.registerAgent(userId, customAgent);
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