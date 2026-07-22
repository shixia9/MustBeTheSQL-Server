package com.sql.logic.engine.domain.agentic.config;

import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.sql.logic.engine.application.service.VectorSearchService;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.python.SimplePythonExecutor;
import com.sql.logic.engine.domain.agent.service.SqlExecutionService;
import com.sql.logic.engine.domain.agent.core.AgentEventSinkRegistry;
import com.sql.logic.engine.domain.agent.core.AgentSseCodec;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agentic.action.*;
import com.sql.logic.engine.domain.agentic.agent.*;
import com.sql.logic.engine.domain.agentic.context.ContextBudgetConfig;
import com.sql.logic.engine.domain.agentic.context.ContextManager;
import com.sql.logic.engine.domain.agentic.core.AgentMemory;
import com.sql.logic.engine.domain.agentic.memory.*;
import com.sql.logic.engine.domain.agentic.plan.InMemoryPlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import com.sql.logic.engine.domain.agentic.profile.ProfileRenderer;
import com.sql.logic.engine.domain.agentic.resource.KnowledgeResource;
import com.sql.logic.engine.domain.agentic.routing.ComplexityRouter;
import com.sql.logic.engine.domain.agentic.skill.SkillRegistry;
import com.sql.logic.engine.domain.memory.MemoryDomainService;
import com.sql.logic.engine.infrastructure.dao.TaskProgressSnapshotDao;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;

@Configuration
public class AgenticAutoConfiguration {

    // ======================== Framework Beans ========================

    @Bean
    public ProfileRenderer profileRenderer() {
        return new ProfileRenderer();
    }

    @Bean
    @Primary
    public AgentMemory agentMemory(MemoryDomainService memoryDomainService,
                                   LLMImportanceScorer importanceScorer,
                                   LLMInsightExtractor insightExtractor) {
        HybridAgentMemory memory = new HybridAgentMemory(memoryDomainService);
        memory.setImportanceScorer(importanceScorer);
        memory.setInsightExtractor(insightExtractor);
        return memory;
    }

    @Bean
    public SimpleAgentMemory simpleAgentMemory(MemoryDomainService memoryDomainService) {
        return new SimpleAgentMemory(memoryDomainService, null, null);
    }

    @Bean
    public PlanMemory planMemory() {
        return new InMemoryPlanMemory();
    }

    // ======================== Phase 3: Context Management ========================

    @Bean
    public ContextBudgetConfig contextBudgetConfig() {
        return new ContextBudgetConfig();
    }

    @Bean
    public ContextManager contextManager(ContextBudgetConfig config,
                                         LlmClientManager llmClientManager) {
        return new ContextManager(config, llmClientManager);
    }

    @Bean
    public TaskProgressPersistenceService taskProgressPersistenceService(
            TaskProgressSnapshotDao dao) {
        return new TaskProgressPersistenceService(dao);
    }

    @Bean
    public LLMImportanceScorer llmImportanceScorer(LlmClientManager llmClientManager) {
        return new LLMImportanceScorer(llmClientManager);
    }

    @Bean
    public LLMInsightExtractor llmInsightExtractor(LlmClientManager llmClientManager) {
        return new LLMInsightExtractor(llmClientManager);
    }

    // ======================== Phase 4: Complexity Routing ========================

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public ComplexityRouter complexityRouter(LlmClientManager llmClientManager,
                                              PromptManager promptManager) {
        return new ComplexityRouter(llmClientManager, promptManager);
    }

    @Bean
    public ComplexityRouter complexityRouterNoPrompt(LlmClientManager llmClientManager) {
        return new ComplexityRouter(llmClientManager, null);
    }

    // ======================== Phase 4: Multi-Candidate SQL ========================

    @Bean
    public SqlCandidateScorer sqlCandidateScorer(LlmClientManager llmClientManager) {
        return new SqlCandidateScorer(llmClientManager);
    }

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public MultiCandidateSqlAction multiCandidateSqlAction(PromptManager promptManager,
                                                            SqlCandidateScorer scorer) {
        return new MultiCandidateSqlAction(promptManager, scorer, 3);
    }

    // ======================== Phase 4: Skill System ========================

    @Bean
    public SkillRegistry skillRegistry() {
        SkillRegistry registry = new SkillRegistry();
        registry.registerBuiltinSkills();
        return registry;
    }

    // ======================== Phase 1 Actions ========================

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

    // ======================== Phase 2 Actions ========================

    @Bean
    public PlanAction planAction(PlanMemory planMemory) {
        return new PlanAction(planMemory, List.of(
                Map.of("name", "DataScientist", "role", "数据科学家", "goal", "生成并执行 SQL"),
                Map.of("name", "CodeAssistant", "role", "代码工程师", "goal", "执行 Python 数据分析"),
                Map.of("name", "ToolAssistant", "role", "工具专家", "goal", "调用 MCP 外部工具"),
                Map.of("name", "DashboardAssistant", "role", "报告生成者", "goal", "汇总生成报告")
        ));
    }

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public PythonGenerationAction pythonGenerationAction(PromptManager promptManager) {
        return new PythonGenerationAction(promptManager);
    }

    @Bean
    @ConditionalOnClass(SimplePythonExecutor.class)
    public PythonExecutionAction pythonExecutionAction(SimplePythonExecutor executor) {
        return new PythonExecutionAction(executor);
    }

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public PythonAnalyzeAction pythonAnalyzeAction(PromptManager promptManager) {
        return new PythonAnalyzeAction(promptManager);
    }

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public DashboardAction dashboardAction(PromptManager promptManager) {
        return new DashboardAction(promptManager);
    }

    @Bean
    public McpToolAction mcpToolAction() {
        return new McpToolAction();
    }

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public McpToolFixAction mcpToolFixAction(PromptManager promptManager) {
        return new McpToolFixAction(promptManager);
    }

    // ======================== Agents ========================

    @Bean
    public DataScientistAgent dataScientistAgent(
            AgentMemory agentMemory,
            SqlGenerationAction sqlGenerationAction,
            SqlExecutionAction sqlExecutionAction,
            SqlFixAction sqlFixAction,
            MultiCandidateSqlAction multiCandidateSqlAction,
            ProfileRenderer profileRenderer,
            ContextManager contextManager,
            TaskProgressPersistenceService persistenceService,
            SkillRegistry skillRegistry,
            LlmClientManager llmClientManager) {
        DataScientistAgent agent = new DataScientistAgent();
        agent.bind(agentMemory);
        agent.bind(List.of(multiCandidateSqlAction, sqlGenerationAction,
                sqlExecutionAction, sqlFixAction));
        agent.bind(profileRenderer);
        agent.bind(llmClientManager);
        agent.bindContextManager(contextManager);
        agent.bindPersistence(persistenceService);
        agent.bindSkills(skillRegistry);
        agent.build();
        return agent;
    }

    @Bean
    public PlannerAgent plannerAgent(PlanAction planAction, AgentMemory agentMemory,
                                      ProfileRenderer profileRenderer,
                                      SkillRegistry skillRegistry,
                                      LlmClientManager llmClientManager) {
        PlannerAgent agent = new PlannerAgent();
        agent.bind(agentMemory);
        agent.bind(List.of(planAction));
        agent.bind(profileRenderer);
        agent.bind(llmClientManager);
        agent.bindSkills(skillRegistry);
        agent.build();
        return agent;
    }

    @Bean
    public CodeAssistantAgent codeAssistantAgent(AgentMemory agentMemory,
                                                  PythonGenerationAction genAction,
                                                  PythonExecutionAction execAction,
                                                  PythonAnalyzeAction analyzeAction,
                                                  ProfileRenderer profileRenderer,
                                                  ContextManager contextManager,
                                                  TaskProgressPersistenceService persistenceService,
                                                  SkillRegistry skillRegistry,
                                                  LlmClientManager llmClientManager) {
        CodeAssistantAgent agent = new CodeAssistantAgent();
        agent.bind(agentMemory);
        agent.bind(List.of(genAction, execAction, analyzeAction));
        agent.bind(profileRenderer);
        agent.bind(llmClientManager);
        agent.bindContextManager(contextManager);
        agent.bindPersistence(persistenceService);
        agent.bindSkills(skillRegistry);
        agent.build();
        return agent;
    }

    @Bean
    public DashboardAssistantAgent dashboardAssistantAgent(AgentMemory agentMemory,
                                                            DashboardAction dashboardAction,
                                                            ProfileRenderer profileRenderer,
                                                            SkillRegistry skillRegistry,
                                                            LlmClientManager llmClientManager) {
        DashboardAssistantAgent agent = new DashboardAssistantAgent();
        agent.bind(agentMemory);
        agent.bind(List.of(dashboardAction));
        agent.bind(profileRenderer);
        agent.bind(llmClientManager);
        agent.bindSkills(skillRegistry);
        agent.build();
        return agent;
    }

    @Bean
    public ToolAssistantAgent toolAssistantAgent(AgentMemory agentMemory,
                                                  McpToolAction mcpToolAction,
                                                  McpToolFixAction mcpToolFixAction,
                                                  ProfileRenderer profileRenderer,
                                                  SkillRegistry skillRegistry,
                                                  LlmClientManager llmClientManager) {
        ToolAssistantAgent agent = new ToolAssistantAgent();
        agent.bind(agentMemory);
        agent.bind(List.of(mcpToolAction, mcpToolFixAction));
        agent.bind(profileRenderer);
        agent.bind(llmClientManager);
        agent.bindSkills(skillRegistry);
        agent.build();
        return agent;
    }

    @Bean
    public ManagerAgent managerAgent(PlanMemory planMemory, AgentMemory agentMemory,
                                      PlannerAgent plannerAgent,
                                      DashboardAssistantAgent dashboardAgent,
                                      DataScientistAgent dataScientistAgent,
                                      CodeAssistantAgent codeAssistantAgent,
                                      ToolAssistantAgent toolAssistantAgent,
                                      ComplexityRouter complexityRouter,
                                      ProfileRenderer profileRenderer,
                                      ContextManager contextManager,
                                      TaskProgressPersistenceService persistenceService,
                                      SkillRegistry skillRegistry,
                                      LlmClientManager llmClientManager,
                                      AgentEventSinkRegistry eventSinkRegistry,
                                      AgentSseCodec agentSseCodec) {
        ManagerAgent agent = new ManagerAgent();
        agent.setPlanMemory(planMemory);
        agent.setPlannerAgent(plannerAgent);
        agent.setDashboardAgent(dashboardAgent);
        agent.setDataScientistAgent(dataScientistAgent);
        agent.setComplexityRouter(complexityRouter);
        agent.setEventSinkRegistry(eventSinkRegistry);
        agent.setCodec(agentSseCodec);
        agent.bind(agentMemory);
        agent.bind(profileRenderer);
        agent.bind(llmClientManager);
        agent.bindContextManager(contextManager);
        agent.bindPersistence(persistenceService);
        agent.bindSkills(skillRegistry);
        agent.hire(dataScientistAgent);
        agent.hire(codeAssistantAgent);
        agent.hire(toolAssistantAgent);
        agent.build();
        return agent;
    }

    // ======================== Resource Beans ========================

    @Bean
    @ConditionalOnClass(VectorSearchService.class)
    public KnowledgeResource knowledgeResource(VectorSearchService vectorSearchService) {
        return new KnowledgeResource(vectorSearchService, null, null);
    }

    // ======================== Orchestrator ========================

    @Bean
    public AgentOrchestrator agentOrchestrator(
            PlannerAgent plannerAgent,
            ManagerAgent managerAgent,
            DataScientistAgent dataScientistAgent,
            CodeAssistantAgent codeAssistantAgent,
            DashboardAssistantAgent dashboardAssistantAgent,
            ToolAssistantAgent toolAssistantAgent) throws GraphStateException {
        return new AgentOrchestrator(plannerAgent, managerAgent,
                dataScientistAgent, codeAssistantAgent,
                dashboardAssistantAgent, toolAssistantAgent);
    }
}
