package com.sql.logic.engine.application.service;

import com.sql.logic.engine.domain.agent.core.AiAgent;
import com.sql.logic.engine.domain.agent.core.AiAgentManager;
import com.sql.logic.engine.infrastructure.dao.DbConnectionConfDao;
import com.sql.logic.engine.infrastructure.po.DbConnectionConf;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class SQLGenerateAppService {

    private final AiAgentManager aiAgentManager;
    private final UserAppService userAppService;
    private final QueryHistoryAppService queryHistoryAppService;
    private final DbConnectionConfDao dbConnectionConfDao;

    public SQLGenerateAppService(AiAgentManager aiAgentManager, 
                                 UserAppService userAppService,
                                 QueryHistoryAppService queryHistoryAppService,
                                 DbConnectionConfDao dbConnectionConfDao) {
        this.aiAgentManager = aiAgentManager;
        this.userAppService = userAppService;
        this.queryHistoryAppService = queryHistoryAppService;
        this.dbConnectionConfDao = dbConnectionConfDao;
    }

    public Flux<String> generateSqlStream(Long userId, String userInput, Long connectionId, List<String> tableNames, String manualSchemaContext, String strategyName, Long parentHistoryId) {
        // Check user status and AI token quota before generation
        try {
            userAppService.checkBeforeGeneration(userId);
        } catch (Exception e) {
            return Flux.error(e);
        }

        // Get dynamically assembled agent for this user (or fallback to default)
        AiAgent agent = aiAgentManager.getAgent(userId);
        
        // Pass the token deduction callback which triggers precisely after completion
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
                queryHistoryAppService.recordGeneration(userId, userInput, connectionId, dbName, generatedSql, strategyName, tokens, parentHistoryId);
                
            } catch (Exception e) {
                System.err.println("Audit Log: Token deduction/history exception for user " + userId + ": " + e.getMessage());
            }
        });
    }
}
