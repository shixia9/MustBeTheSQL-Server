package com.sql.logic.engine.domain.agentic.config;

import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.service.SqlExecutionService;
import com.sql.logic.engine.domain.agentic.action.SqlExecutionAction;
import com.sql.logic.engine.domain.agentic.action.SqlFixAction;
import com.sql.logic.engine.domain.agentic.action.SqlGenerationAction;
import com.sql.logic.engine.domain.agentic.agent.DataScientistAgent;
import com.sql.logic.engine.domain.agentic.core.AgentMemory;
import com.sql.logic.engine.domain.agentic.memory.SimpleAgentMemory;
import com.sql.logic.engine.domain.agentic.profile.ProfileRenderer;
import com.sql.logic.engine.domain.memory.MemoryDomainService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring auto-configuration for the Multi-Agent framework (Phase 1).
 * <p>
 * Registers core framework beans and the first concrete agent
 * ({@link DataScientistAgent}) with its actions. All beans are
 * conditional on the presence of the existing infrastructure classes
 * ({@code PromptManager}, {@code SqlExecutionService}, etc.) so that
 * the framework gracefully degrades when those are unavailable.
 * <p>
 * This configuration is additive — it does not modify or replace
 * any existing beans from the single-agent StateGraph.
 */
@Configuration
public class AgenticAutoConfiguration {

    // ======================== Framework Beans ========================

    @Bean
    public ProfileRenderer profileRenderer() {
        return new ProfileRenderer();
    }

    @Bean
    public AgentMemory agentMemory(MemoryDomainService memoryDomainService) {
        return new SimpleAgentMemory(memoryDomainService, null, null);
    }

    // ======================== Actions ========================

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public SqlGenerationAction sqlGenerationAction(PromptManager promptManager) {
        return new SqlGenerationAction(promptManager);
    }

    @Bean
    @ConditionalOnClass(SqlExecutionService.class)
    public SqlExecutionAction sqlExecutionAction(SqlExecutionService sqlExecutionService) {
        return new SqlExecutionAction(sqlExecutionService);
    }

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public SqlFixAction sqlFixAction(PromptManager promptManager) {
        return new SqlFixAction(promptManager);
    }

    // ======================== Agents ========================

    @Bean
    public DataScientistAgent dataScientistAgent(
            AgentMemory agentMemory,
            SqlGenerationAction sqlGenerationAction,
            SqlExecutionAction sqlExecutionAction,
            SqlFixAction sqlFixAction,
            ProfileRenderer profileRenderer) {

        DataScientistAgent agent = new DataScientistAgent();
        agent.bind(agentMemory);
        agent.bind(List.of(sqlGenerationAction, sqlExecutionAction, sqlFixAction));
        agent.bind(profileRenderer);
        agent.build();
        return agent;
    }
}
