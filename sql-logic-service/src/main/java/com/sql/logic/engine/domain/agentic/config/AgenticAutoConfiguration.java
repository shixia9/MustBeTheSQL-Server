package com.sql.logic.engine.domain.agentic.config;

import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.sql.logic.engine.application.service.DatabaseMetaDataService;
import com.sql.logic.engine.application.service.VectorSearchService;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.tool.ToolInvocationGuard;
import com.sql.logic.engine.domain.sandbox.SandboxExecutionService;
import com.sql.logic.engine.domain.agent.tool.mcp.McpServerManager;
import com.sql.logic.engine.domain.agent.service.SqlExecutionService;
import com.sql.logic.engine.domain.agent.core.AgentEventSinkRegistry;
import com.sql.logic.engine.domain.agent.core.AgentSseCodec;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agentic.action.*;
import com.sql.logic.engine.domain.agentic.agent.*;
import com.sql.logic.engine.domain.agentic.vis.VisChart;
import com.sql.logic.engine.domain.agentic.vis.VisDashboard;
import com.sql.logic.engine.domain.agentic.context.ContextBudgetConfig;
import com.sql.logic.engine.domain.agentic.context.ContextManager;
import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMemory;
import com.sql.logic.engine.domain.agentic.core.bus.AgentDispatcher;
import com.sql.logic.engine.domain.agentic.core.bus.AgentMessageBus;
import com.sql.logic.engine.domain.agentic.core.bus.BusAgentDispatcher;
import com.sql.logic.engine.domain.agentic.core.bus.BusOrchestrationProperties;
import com.sql.logic.engine.domain.agentic.core.bus.BusWorkerEndpointRegistrar;
import com.sql.logic.engine.domain.agentic.core.bus.BypassAgentDispatcher;
import com.sql.logic.engine.domain.agentic.core.bus.DirectAgentDispatcher;
import com.sql.logic.engine.domain.agentic.core.bus.InMemoryMessageBus;
import com.sql.logic.engine.domain.agentic.memory.*;
import com.sql.logic.engine.domain.agentic.plan.InMemoryPlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import com.sql.logic.engine.domain.agentic.profile.ProfileRenderer;
import com.sql.logic.engine.domain.agentic.resource.KnowledgeResource;
import com.sql.logic.engine.domain.agentic.routing.ComplexityRouter;
import com.sql.logic.engine.domain.agentic.skill.SkillRegistry;
import com.sql.logic.engine.domain.agentic.enrichment.SchemaEnrichmentService;
import com.sql.logic.engine.domain.agentic.workflow.NodeRegistry;
import com.sql.logic.engine.domain.agentic.workflow.WorkflowAgentExecutorImpl;
import com.sql.logic.engine.domain.memory.MemoryDomainService;
import com.sql.logic.engine.infrastructure.dao.TaskProgressSnapshotDao;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
                                         LlmClientManager llmClientManager,
                                         AgentEventSinkRegistry eventSinkRegistry) {
        return new ContextManager(config, llmClientManager, eventSinkRegistry);
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

    // ======================== Message Bus Orchestration ========================

    /**
     * In-JVM message bus.
     */
    @Bean
    public AgentMessageBus agentMessageBus() {
        return new InMemoryMessageBus();
    }

    /**
     * Select the dispatch strategy from {@code bus-orc.mode}:
     * {@code OFF} → direct call (default, zero behaviour change),
     * {@code BYPASS} → direct call + bus mirror (M9a),
     * {@code SWITCH} → bus-mediated request/reply (M9b).
     *
     * <p>In {@code SWITCH} mode the {@link BusWorkerEndpointRegistrar} bean is
     * also created (conditional on the same property); it subscribes every
     * worker before any request can fire, so the dispatcher's replies always
     * find a live worker endpoint.
     */
    @Bean
    public AgentDispatcher agentDispatcher(BusOrchestrationProperties props,
                                           AgentMessageBus bus) {
        return switch (props.getMode()) {
            case OFF -> new DirectAgentDispatcher();
            case BYPASS -> new BypassAgentDispatcher(bus, new DirectAgentDispatcher());
            case SWITCH -> new BusAgentDispatcher(bus, "Manager", props.getDispatcherTimeoutSeconds());
        };
    }

    /**
     * SWITCH-mode only: register a {@link com.sql.logic.engine.domain.agentic.core.bus.BusWorkerEndpoint}
     * for each worker so bus-dispatched tasks reach a real {@code generateReply}.
     * Eager singleton — created at startup, subscribing all workers before the
     * first orchestration request.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "bus-orc.mode", havingValue = "switch")
    public BusWorkerEndpointRegistrar busWorkerEndpointRegistrar(AgentMessageBus bus,
            PlannerAgent plannerAgent,
            DataScientistAgent dataScientistAgent,
            CodeAssistantAgent codeAssistantAgent,
            ToolAssistantAgent toolAssistantAgent,
            DashboardAssistantAgent dashboardAssistantAgent) {
        BusWorkerEndpointRegistrar registrar = new BusWorkerEndpointRegistrar(bus, "Manager");
        registrar.register(plannerAgent);
        registrar.register(dataScientistAgent);
        registrar.register(codeAssistantAgent);
        registrar.register(toolAssistantAgent);
        registrar.register(dashboardAssistantAgent);
        return registrar;
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
    @ConditionalOnClass(SandboxExecutionService.class)
    public PythonExecutionAction pythonExecutionAction(SandboxExecutionService sandboxService) {
        return new PythonExecutionAction(sandboxService);
    }

    @Bean
    @ConditionalOnClass(SandboxExecutionService.class)
    public ShellExecutionAction shellExecutionAction(SandboxExecutionService sandboxService) {
        return new ShellExecutionAction(sandboxService);
    }

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public PythonAnalyzeAction pythonAnalyzeAction(PromptManager promptManager) {
        return new PythonAnalyzeAction(promptManager);
    }

    // ======================== Phase 6: Vis Protocol ========================

    @Bean
    public VisChart visChart() {
        return new VisChart();
    }

    @Bean
    public VisDashboard visDashboard() {
        return new VisDashboard();
    }

    // ======================== Phase 6: Chart Action ========================

    @Bean
    @ConditionalOnClass(SqlExecutionService.class)
    public ChartAction chartAction(SqlExecutionService sqlExecutionService, VisChart visChart) {
        return new ChartAction(sqlExecutionService, visChart);
    }

    // ======================== Phase 2/6: Dashboard Action ========================

    @Bean
    @ConditionalOnClass(PromptManager.class)
    public DashboardAction dashboardAction(PromptManager promptManager, VisDashboard visDashboard,
                                           SqlExecutionService sqlExecutionService) {
        DashboardAction action = new DashboardAction(promptManager, visDashboard);
        action.setSqlExecutionService(sqlExecutionService);
        return action;
    }

    @Bean
    @ConditionalOnClass(McpServerManager.class)
    public McpToolAction mcpToolAction(McpServerManager mcpServerManager,
                                        ToolInvocationGuard toolInvocationGuard) {
        return new McpToolAction(mcpServerManager, toolInvocationGuard);
    }

    @Bean
    @ConditionalOnClass({PromptManager.class, McpServerManager.class})
    public McpToolFixAction mcpToolFixAction(PromptManager promptManager,
                                              McpServerManager mcpServerManager) {
        return new McpToolFixAction(mcpServerManager, promptManager);
    }

    // ======================== Agents ========================

    @Bean
    public DataScientistAgent dataScientistAgent(
            AgentMemory agentMemory,
            SqlGenerationAction sqlGenerationAction,
            SqlExecutionAction sqlExecutionAction,
            SqlFixAction sqlFixAction,
            MultiCandidateSqlAction multiCandidateSqlAction,
            ChartAction chartAction,
            ProfileRenderer profileRenderer,
            ContextManager contextManager,
            TaskProgressPersistenceService persistenceService,
            SkillRegistry skillRegistry,
            LlmClientManager llmClientManager) {
        DataScientistAgent agent = new DataScientistAgent();
        agent.bind(agentMemory);
        agent.bind(List.of(multiCandidateSqlAction, chartAction,
                sqlGenerationAction, sqlExecutionAction, sqlFixAction));
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
                                      AgentSseCodec agentSseCodec,
                                      AgentDispatcher agentDispatcher) {
        ManagerAgent agent = new ManagerAgent();
        agent.setPlanMemory(planMemory);
        agent.setPlannerAgent(plannerAgent);
        agent.setDashboardAgent(dashboardAgent);
        agent.setDataScientistAgent(dataScientistAgent);
        agent.setComplexityRouter(complexityRouter);
        agent.setEventSinkRegistry(eventSinkRegistry);
        agent.setCodec(agentSseCodec);
        agent.setDispatcher(agentDispatcher);
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

    // ======================== Platform Beans ========================

    @Bean
    public NodeRegistry nodeRegistry() {
        return new NodeRegistry();
    }

    // ======================== Workflow Engine Beans ========================

    @Bean
    public WorkflowAgentExecutorImpl workflowAgentExecutor(
            ManagerAgent managerAgent,
            PlannerAgent plannerAgent,
            DataScientistAgent dataScientistAgent,
            CodeAssistantAgent codeAssistantAgent,
            DashboardAssistantAgent dashboardAssistantAgent,
            ToolAssistantAgent toolAssistantAgent,
            AgentSseCodec agentSseCodec,
            DatabaseMetaDataService databaseMetaDataService) {
        Map<String, Agent> agentMap = new LinkedHashMap<>();
        registerAgent(agentMap, managerAgent);
        registerAgent(agentMap, plannerAgent);
        registerAgent(agentMap, dataScientistAgent);
        registerAgent(agentMap, codeAssistantAgent);
        registerAgent(agentMap, dashboardAssistantAgent);
        registerAgent(agentMap, toolAssistantAgent);
        return new WorkflowAgentExecutorImpl(agentMap, agentSseCodec, databaseMetaDataService);
    }

    private void registerAgent(Map<String, Agent> map, Agent agent) {
        if (agent == null) return;
        // Register by class simple name (e.g. "ManagerAgent")
        map.put(agent.getClass().getSimpleName(), agent);
        // Register by profile name (e.g. "Manager")
        String profileName = agent.name();
        if (profileName != null && !profileName.isBlank() && !map.containsKey(profileName)) {
            map.put(profileName, agent);
        }
    }

    // ======================== Schema Enrichment Thread Pool ========================

    /**
     * Dedicated thread pool for background schema enrichment via LLM semantic filtering.
     * Bounded pool (1–2 threads) with CallerRunsPolicy — if the queue is full the
     * calling thread executes the task synchronously, preserving correctness.
     */
    @Bean("schemaLinkingExecutor")
    public ExecutorService schemaLinkingExecutor() {
        return new ThreadPoolExecutor(
                1, 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10),
                r -> {
                    Thread t = new Thread(r, "schema-linking-" + System.currentTimeMillis() % 10000);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
