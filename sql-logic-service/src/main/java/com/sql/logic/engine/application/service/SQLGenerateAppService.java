package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.core.AiAgent;
import com.sql.logic.engine.domain.agent.core.AiAgentManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import com.sql.logic.engine.infrastructure.po.UserLlmConfig;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class SQLGenerateAppService {

    private final AiAgentManager aiAgentManager;
    private final LlmClientManager llmClientManager;
    private final UserAppService userAppService;
    private final QueryHistoryAppService queryHistoryAppService;
    private final DbConnectionConfDao dbConnectionConfDao;
    private final LlmConfigAppService llmConfigAppService;

    public SQLGenerateAppService(AiAgentManager aiAgentManager,
                                 LlmClientManager llmClientManager,
                                 UserAppService userAppService,
                                 QueryHistoryAppService queryHistoryAppService,
                                 DbConnectionConfDao dbConnectionConfDao,
                                 LlmConfigAppService llmConfigAppService) {
        this.aiAgentManager = aiAgentManager;
        this.llmClientManager = llmClientManager;
        this.userAppService = userAppService;
        this.queryHistoryAppService = queryHistoryAppService;
        this.dbConnectionConfDao = dbConnectionConfDao;
        this.llmConfigAppService = llmConfigAppService;
    }

    public Flux<String> generateSqlStream(Long userId, String userInput, Long connectionId, List<String> tableNames, String manualSchemaContext, Long llmConfigId, String strategyName, Long parentHistoryId) {
        // Check user status and AI token quota before generation
        try {
            userAppService.checkBeforeGeneration(userId);
        } catch (Exception e) {
            return Flux.error(e);
        }

        // Get the user's agent (one per user, prompt builder)
        AiAgent agent = aiAgentManager.getAgent(userId);

        // Resolve which LLM strategy to use based on the selected config
        LLMStrategy strategy;
        if (llmConfigId != null && llmConfigId > 0) {
            // User explicitly selected a specific config
            strategy = llmClientManager.getClient(llmConfigId);
        } else if (llmConfigId != null && llmConfigId == 0) {
            // User explicitly selected system default
            strategy = llmClientManager.getClient(0L);
        } else {
            // Use user's default config, or fall back to system default
            strategy = llmClientManager.getDefaultForUser(userId);
        }

        // Determine model name for history recording
        String modelName = "Default";
        if (llmConfigId != null && llmConfigId > 0) {
            UserLlmConfig config = llmConfigAppService.getConfigById(llmConfigId);
            if (config != null) {
                modelName = config.getProviderType() + "/" + (config.getModelName() != null ? config.getModelName() : "default");
            }
        } else if (strategyName != null && !strategyName.isBlank()) {
            // Backward compatibility
            modelName = strategyName;
        }

        final String finalModelName = modelName;

        // Generate SQL using the agent with the resolved strategy
        return agent.generateSqlStream(userInput, connectionId, tableNames, manualSchemaContext, (tokens, generatedSql) -> {
            try {
                userAppService.deductTokens(userId, tokens);

                String dbName = "Unknown Database";
                if (connectionId != null) {
                    DbConnectionConf conn = dbConnectionConfDao.selectById(connectionId);
                    if (conn != null) {
                        dbName = conn.getName() + " (" + conn.getDbType() + ")";
                    }
                }
                queryHistoryAppService.recordGeneration(userId, userInput, connectionId, dbName, generatedSql, finalModelName, tokens, parentHistoryId, llmConfigId);

            } catch (Exception e) {
                System.err.println("Audit Log: Token deduction/history exception for user " + userId + ": " + e.getMessage());
            }
        }, strategy);
    }
}