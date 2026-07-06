package com.sql.logic.engine.domain.agent.config;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.edge.AnalyzerEdge;
import com.sql.logic.engine.domain.agent.edge.FeasibilityAssessmentEdge;
import com.sql.logic.engine.domain.agent.edge.HitlEdge;
import com.sql.logic.engine.domain.agent.edge.HitlGateEdge;
import com.sql.logic.engine.domain.agent.edge.PlanDispatchEdge;
import com.sql.logic.engine.domain.agent.edge.SqlExecutionEdge;
import com.sql.logic.engine.domain.agent.edge.TaskDispatchEdge;
import com.sql.logic.engine.domain.agent.node.AnalyzerNode;
import com.sql.logic.engine.domain.agent.node.EvidenceRecallNode;
import com.sql.logic.engine.domain.agent.node.FeasibilityAssessmentNode;
import com.sql.logic.engine.domain.agent.node.HitlGateNode;
import com.sql.logic.engine.domain.agent.node.HitlNode;
import com.sql.logic.engine.domain.agent.node.PlanDispatchNode;
import com.sql.logic.engine.domain.agent.node.PlannerNode;
import com.sql.logic.engine.domain.agent.node.PythonAnalyzeNode;
import com.sql.logic.engine.domain.agent.node.PythonExecuteNode;
import com.sql.logic.engine.domain.agent.node.PythonGeneratorNode;
import com.sql.logic.engine.domain.agent.node.ReportNode;
import com.sql.logic.engine.domain.agent.node.SchemaLinkingNode;
import com.sql.logic.engine.domain.agent.node.SqlExecutionNode;
import com.sql.logic.engine.domain.agent.node.SqlFixerNode;
import com.sql.logic.engine.domain.agent.node.SqlGenerationNode;
import com.sql.logic.engine.domain.agent.node.SummarizeNode;
import com.sql.logic.engine.domain.agent.node.TaskDispatchNode;
import com.sql.logic.engine.domain.agent.node.TaskSplitNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Configuration that wires the SQL Agent StateGraph.
 * <p>
 * Adds a human-in-the-loop gate (HITL_GATE → HITL) after the
 * planner and the Python sandbox analysis chain (PYTHON_GENERATION → PYTHON_EXECUTION
 * → PYTHON_ANALYSIS) to the plan dispatcher. See {@link #sqlAgentGraph} javadoc.
 * <p>
 * The graph is compiled by {@code SqlAgentRunner} with a {@code MemorySaver} and
 * {@code interruptBefore(HITL)} so the compile-time interrupt gate hooks the
 * checkpoint-based pause/resume used by the HITL flow.
 */
@Configuration
public class SqlAgentGraphConfiguration {

    @Value("${phase-b.task-split.enabled:false}")
    private boolean taskSplitEnabled;

    /**
     * Define the KeyStrategyFactory — all state keys use ReplaceStrategy
     * (downstream node overwrites, no accumulation).
     */
    @Bean
    public KeyStrategyFactory sqlAgentKeyStrategyFactory() {
        return () -> {
            Map<String, KeyStrategy> strategies = new LinkedHashMap<>();

            // Input keys
            strategies.put(SqlAgentSpec.StateKey.INPUT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.USER_ID, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.CONNECTION_ID, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.LLM_CONFIG_ID, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.WORKSPACE_ID, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.THREAD_ID, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SESSION_ID, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.DB_TYPE, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SCHEMA_NAME, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.TABLE_NAMES, new ReplaceStrategy());

            // Evidence Recall keys
            strategies.put(SqlAgentSpec.StateKey.REWRITE_QUERY, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.EVIDENCE, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.EVIDENCE_GLOSSARY, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.EVIDENCE_FAQ, new ReplaceStrategy());

            // Schema Linking keys
            strategies.put(SqlAgentSpec.StateKey.TABLE_RELATION, new ReplaceStrategy());

            // Feasibility keys
            strategies.put(SqlAgentSpec.StateKey.FEASIBILITY_RESULT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.NEXT_NODE, new ReplaceStrategy());

            // Planning keys
            strategies.put(SqlAgentSpec.StateKey.PLAN, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.CURRENT_STEP, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.REPAIR_COUNT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.EXECUTION_OUTPUT, new ReplaceStrategy());

            // HITL keys
            strategies.put(SqlAgentSpec.StateKey.AUTO_CONFIRM, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.NEEDS_HUMAN_REVIEW, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.CONFIRMATION_APPROVED, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, new ReplaceStrategy());

            // SQL execution keys
            strategies.put(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SQL_ERROR, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT, new ReplaceStrategy());

            // Python keys
            strategies.put(SqlAgentSpec.StateKey.PYTHON_CODE, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.PYTHON_RESULT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.PYTHON_ANALYSIS_RESULT, new ReplaceStrategy());

            // Report key
            strategies.put(SqlAgentSpec.StateKey.REPORT_RESULT, new ReplaceStrategy());

            // Task Split keys
            strategies.put(SqlAgentSpec.StateKey.COMPLEXITY, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SUBTASKS, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.CURRENT_SUBTASK, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SUBTASK_RESULTS, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.USER_MEMORY, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.TRACE_CONTEXT, new ReplaceStrategy());

            return strategies;
        };
    }

    /**
     * Define the SQL Agent StateGraph bean.
     * <p>
     * Phase 4 topology (built on the Phase 3 feasibility/planner/dispatch core):
     * <pre>
     * START → EVIDENCE_RECALL → SCHEMA_LINKING → FEASIBILITY_ASSESSMENT
     *                                                 │ (conditional)
     *                                                 ├─ 《数据分析》 → PLANNER
     *                                                 └─ 其他       → REPORT → END
     *                                 PLANNER → HITL_GATE
     *                                 HITL_GATE (conditional: HitlGateEdge)
     *                                                 ├─ needs review   → HITL (interrupt-before)
     *                                                 └─ no review      → PLAN_DISPATCH
     *                                 HITL (conditional: HitlEdge, resumed after human decision)
     *                                                 ├─ approved       → PLAN_DISPATCH
     *                                                 ├─ rejected (< 3) → PLANNER
     *                                                 └─ rejected ≥ 3   → END
     *                                 PLAN_DISPATCH (conditional)
     *                                                 ├─ SQL_GENERATION → SQL_EXECUTION
     *                                                 │                       │ (conditional)
     *                                                 │                       ├─ success → PLAN_DISPATCH
     *                                                 │                       └─ error<2 → SQL_FIXER → SQL_EXECUTION
     *                                                 ├─ PYTHON_GENERATION → PYTHON_EXECUTION → PYTHON_ANALYSIS → PLAN_DISPATCH
     *                                                 ├─ REPORT → END
     *                                                 └─ END
     * </pre>
     */
    @Bean
    public StateGraph sqlAgentGraph(EvidenceRecallNode evidenceRecallNode,
                                     SchemaLinkingNode schemaLinkingNode,
                                     FeasibilityAssessmentNode feasibilityAssessmentNode,
                                     PlannerNode plannerNode,
                                     HitlGateNode hitlGateNode,
                                     HitlNode hitlNode,
                                     PlanDispatchNode planDispatchNode,
                                     SqlGenerationNode sqlGenerationNode,
                                     SqlExecutionNode sqlExecutionNode,
                                     SqlFixerNode sqlFixerNode,
                                     PythonGeneratorNode pythonGeneratorNode,
                                     PythonExecuteNode pythonExecuteNode,
                                     PythonAnalyzeNode pythonAnalyzeNode,
                                     ReportNode reportNode,
                                     AnalyzerNode analyzerNode,
                                     TaskSplitNode taskSplitNode,
                                     TaskDispatchNode taskDispatchNode,
                                     SummarizeNode summarizeNode,
                                     FeasibilityAssessmentEdge feasibilityEdge,
                                     HitlGateEdge hitlGateEdge,
                                     HitlEdge hitlEdge,
                                     PlanDispatchEdge planDispatchEdge,
                                     SqlExecutionEdge sqlExecutionEdge,
                                     AnalyzerEdge analyzerEdge,
                                     TaskDispatchEdge taskDispatchEdge,
                                     KeyStrategyFactory sqlAgentKeyStrategyFactory) throws GraphStateException {

        // Conditional-edge mappings: the KEY is what the edge returns, the VALUE is
        // the target node id. Every string an edge can return MUST be a key here, or
        // the runtime throws missingNodeInEdgeMapping.
        Map<String, String> feasibilityRouting = new LinkedHashMap<>();
        feasibilityRouting.put(SqlAgentSpec.Node.PLANNER, SqlAgentSpec.Node.PLANNER);
        feasibilityRouting.put(SqlAgentSpec.Node.REPORT, SqlAgentSpec.Node.REPORT);

        Map<String, String> hitlGateRouting = new LinkedHashMap<>();
        hitlGateRouting.put(SqlAgentSpec.Node.HITL, SqlAgentSpec.Node.HITL);
        hitlGateRouting.put(SqlAgentSpec.Node.PLAN_DISPATCH, SqlAgentSpec.Node.PLAN_DISPATCH);

        Map<String, String> hitlRouting = new LinkedHashMap<>();
        hitlRouting.put(SqlAgentSpec.Node.PLAN_DISPATCH, SqlAgentSpec.Node.PLAN_DISPATCH);
        hitlRouting.put(SqlAgentSpec.Node.PLANNER, SqlAgentSpec.Node.PLANNER);
        hitlRouting.put(StateGraph.END, StateGraph.END);

        Map<String, String> planDispatchRouting = new LinkedHashMap<>();
        planDispatchRouting.put(SqlAgentSpec.Node.SQL_GENERATION, SqlAgentSpec.Node.SQL_GENERATION);
        planDispatchRouting.put(SqlAgentSpec.Node.PYTHON_GENERATION, SqlAgentSpec.Node.PYTHON_GENERATION);
        planDispatchRouting.put(SqlAgentSpec.Node.REPORT, SqlAgentSpec.Node.REPORT);
        planDispatchRouting.put(StateGraph.END, StateGraph.END);

        Map<String, String> sqlExecutionRouting = new LinkedHashMap<>();
        sqlExecutionRouting.put(SqlAgentSpec.Node.SQL_FIXER, SqlAgentSpec.Node.SQL_FIXER);
        sqlExecutionRouting.put(SqlAgentSpec.Node.PLAN_DISPATCH, SqlAgentSpec.Node.PLAN_DISPATCH);

        Map<String, String> analyzerRouting = new LinkedHashMap<>();
        analyzerRouting.put(SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT, SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT);
        analyzerRouting.put(SqlAgentSpec.Node.TASK_SPLIT, SqlAgentSpec.Node.TASK_SPLIT);
        analyzerRouting.put(SqlAgentSpec.Node.REPORT, SqlAgentSpec.Node.REPORT);

        Map<String, String> taskDispatchRouting = new LinkedHashMap<>();
        taskDispatchRouting.put(SqlAgentSpec.Node.SQL_GENERATION, SqlAgentSpec.Node.SQL_GENERATION);
        taskDispatchRouting.put(SqlAgentSpec.Node.SUMMARIZE, SqlAgentSpec.Node.SUMMARIZE);

        StateGraph graph = new StateGraph(SqlAgentSpec.GRAPH_NAME, sqlAgentKeyStrategyFactory)
                // ---- Register nodes ----
                .addNode(SqlAgentSpec.Node.EVIDENCE_RECALL, AsyncNodeAction.node_async(evidenceRecallNode))
                .addNode(SqlAgentSpec.Node.SCHEMA_LINKING, AsyncNodeAction.node_async(schemaLinkingNode))
                .addNode(SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT, AsyncNodeAction.node_async(feasibilityAssessmentNode))
                .addNode(SqlAgentSpec.Node.PLANNER, AsyncNodeAction.node_async(plannerNode))
                .addNode(SqlAgentSpec.Node.HITL_GATE, AsyncNodeAction.node_async(hitlGateNode))
                .addNode(SqlAgentSpec.Node.HITL, AsyncNodeAction.node_async(hitlNode))
                .addNode(SqlAgentSpec.Node.PLAN_DISPATCH, AsyncNodeAction.node_async(planDispatchNode))
                .addNode(SqlAgentSpec.Node.SQL_GENERATION, AsyncNodeAction.node_async(sqlGenerationNode))
                .addNode(SqlAgentSpec.Node.SQL_EXECUTION, AsyncNodeAction.node_async(sqlExecutionNode))
                .addNode(SqlAgentSpec.Node.SQL_FIXER, AsyncNodeAction.node_async(sqlFixerNode))
                .addNode(SqlAgentSpec.Node.PYTHON_GENERATION, AsyncNodeAction.node_async(pythonGeneratorNode))
                .addNode(SqlAgentSpec.Node.PYTHON_EXECUTION, AsyncNodeAction.node_async(pythonExecuteNode))
                .addNode(SqlAgentSpec.Node.PYTHON_ANALYSIS, AsyncNodeAction.node_async(pythonAnalyzeNode))
                .addNode(SqlAgentSpec.Node.REPORT, AsyncNodeAction.node_async(reportNode))

                // ---- Edges ----
                .addEdge(StateGraph.START, SqlAgentSpec.Node.EVIDENCE_RECALL)
                .addEdge(SqlAgentSpec.Node.EVIDENCE_RECALL, SqlAgentSpec.Node.SCHEMA_LINKING);

        if (taskSplitEnabled) {
            graph.addEdge(SqlAgentSpec.Node.SCHEMA_LINKING, SqlAgentSpec.Node.ANALYZER);
        } else {
            graph.addEdge(SqlAgentSpec.Node.SCHEMA_LINKING, SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT);
        }

        graph

                // Feasibility gate → PLANNER (analysis) or REPORT (clarify/chat)
                .addConditionalEdges(SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT,
                        AsyncEdgeAction.edge_async(feasibilityEdge), feasibilityRouting)

                // Planner → HITL gate (Phase 4 human-in-the-loop entry point)
                .addEdge(SqlAgentSpec.Node.PLANNER, SqlAgentSpec.Node.HITL_GATE)

                // HITL gate: needs review → HITL interrupt ; else straight to dispatcher
                .addConditionalEdges(SqlAgentSpec.Node.HITL_GATE,
                        AsyncEdgeAction.edge_async(hitlGateEdge), hitlGateRouting)

                // HITL interrupt: on resume, route approved → PLAN_DISPATCH, rejected → PLANNER, ≥3 → END
                .addConditionalEdges(SqlAgentSpec.Node.HITL,
                        AsyncEdgeAction.edge_async(hitlEdge), hitlRouting)

                // Dispatcher routes to the next step's tool node (or END)
                .addConditionalEdges(SqlAgentSpec.Node.PLAN_DISPATCH,
                        AsyncEdgeAction.edge_async(planDispatchEdge), planDispatchRouting)

                // SQL step: generate → execute
                .addEdge(SqlAgentSpec.Node.SQL_GENERATION, SqlAgentSpec.Node.SQL_EXECUTION)
                // SQL execution: success/again → PLAN_DISPATCH ; error<2 → SQL_FIXER
                .addConditionalEdges(SqlAgentSpec.Node.SQL_EXECUTION,
                        AsyncEdgeAction.edge_async(sqlExecutionEdge), sqlExecutionRouting)
                // Fixed SQL re-executes the same step
                .addEdge(SqlAgentSpec.Node.SQL_FIXER, SqlAgentSpec.Node.SQL_EXECUTION)

                // Python step: generate → execute → analyze → back to dispatcher for the next step
                .addEdge(SqlAgentSpec.Node.PYTHON_GENERATION, SqlAgentSpec.Node.PYTHON_EXECUTION)
                .addEdge(SqlAgentSpec.Node.PYTHON_EXECUTION, SqlAgentSpec.Node.PYTHON_ANALYSIS)
                .addEdge(SqlAgentSpec.Node.PYTHON_ANALYSIS, SqlAgentSpec.Node.PLAN_DISPATCH)

                // Report terminates the graph
                .addEdge(SqlAgentSpec.Node.REPORT, StateGraph.END);

        // ---- Task split workflow ----
        if (taskSplitEnabled) {
            graph.addNode(SqlAgentSpec.Node.ANALYZER, AsyncNodeAction.node_async(analyzerNode))
                 .addNode(SqlAgentSpec.Node.TASK_SPLIT, AsyncNodeAction.node_async(taskSplitNode))
                 .addNode(SqlAgentSpec.Node.TASK_DISPATCH, AsyncNodeAction.node_async(taskDispatchNode))
                 .addNode(SqlAgentSpec.Node.SUMMARIZE, AsyncNodeAction.node_async(summarizeNode));

            graph.addConditionalEdges(SqlAgentSpec.Node.ANALYZER,
                     AsyncEdgeAction.edge_async(analyzerEdge), analyzerRouting)
                 .addEdge(SqlAgentSpec.Node.TASK_SPLIT, SqlAgentSpec.Node.TASK_DISPATCH)
                 .addConditionalEdges(SqlAgentSpec.Node.TASK_DISPATCH,
                         AsyncEdgeAction.edge_async(taskDispatchEdge), taskDispatchRouting)
                 .addEdge(SqlAgentSpec.Node.SUMMARIZE, SqlAgentSpec.Node.REPORT);
        }

        return graph;
    }

    /**
     * MemorySaver for graph state checkpointing.
     * Required for HITL (interrupt-before) pattern in Phase 4.
     */
    @Bean
    public MemorySaver sqlAgentMemorySaver() {
        return new MemorySaver();
    }
}