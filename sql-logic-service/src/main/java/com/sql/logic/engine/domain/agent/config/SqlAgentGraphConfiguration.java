package com.sql.logic.engine.domain.agent.config;

import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.node.EvidenceRecallNode;
import com.sql.logic.engine.domain.agent.node.ReportNode;
import com.sql.logic.engine.domain.agent.node.SqlGenerationNode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Configuration that wires the SQL Agent StateGraph.
 * <p>
 * Phase 1 minimal chain:
 * <pre>
 * START → EVIDENCE_RECALL → SQL_GENERATION → REPORT → END
 * </pre>
 * <p>
 * Additional nodes and edges will be added in subsequent phases:
 * - Phase 2: SCHEMA_LINKING, FEASIBILITY_ASSESSMENT
 * - Phase 3: PLANNER, HITL, SQL_EXECUTION, SQL_FIXER
 * - Phase 4: PYTHON_GENERATION, PYTHON_EXECUTION, PYTHON_ANALYSIS
 */
@Configuration
public class SqlAgentGraphConfiguration {

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
            strategies.put(SqlAgentSpec.StateKey.CONNECTION_ID, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.LLM_CONFIG_ID, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.DB_TYPE, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.TABLE_NAMES, new ReplaceStrategy());

            // Evidence Recall keys
            strategies.put(SqlAgentSpec.StateKey.REWRITE_QUERY, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.EVIDENCE, new ReplaceStrategy());

            // Schema Linking keys
            strategies.put(SqlAgentSpec.StateKey.TABLE_RELATION, new ReplaceStrategy());

            // Feasibility keys
            strategies.put(SqlAgentSpec.StateKey.FEASIBILITY_RESULT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.NEXT_NODE, new ReplaceStrategy());

            // Planning keys
            strategies.put(SqlAgentSpec.StateKey.PLAN, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.CURRENT_STEP, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.REPAIR_COUNT, new ReplaceStrategy());

            // HITL keys
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

            return strategies;
        };
    }

    /**
     * Define the SQL Agent StateGraph bean.
     * <p>
     * Phase 1: START → EVIDENCE_RECALL → SQL_GENERATION → REPORT → END
     */
    @Bean
    public StateGraph sqlAgentGraph(EvidenceRecallNode evidenceRecallNode,
                                     SqlGenerationNode sqlGenerationNode,
                                     ReportNode reportNode,
                                     KeyStrategyFactory sqlAgentKeyStrategyFactory) throws GraphStateException {

        return new StateGraph(SqlAgentSpec.GRAPH_NAME, sqlAgentKeyStrategyFactory)
                // Register nodes
                .addNode(SqlAgentSpec.Node.EVIDENCE_RECALL, AsyncNodeAction.node_async(evidenceRecallNode))
                .addNode(SqlAgentSpec.Node.SQL_GENERATION, AsyncNodeAction.node_async(sqlGenerationNode))
                .addNode(SqlAgentSpec.Node.REPORT, AsyncNodeAction.node_async(reportNode))
                // Wire edges — Phase 1 linear chain
                .addEdge(StateGraph.START, SqlAgentSpec.Node.EVIDENCE_RECALL)
                .addEdge(SqlAgentSpec.Node.EVIDENCE_RECALL, SqlAgentSpec.Node.SQL_GENERATION)
                .addEdge(SqlAgentSpec.Node.SQL_GENERATION, SqlAgentSpec.Node.REPORT)
                .addEdge(SqlAgentSpec.Node.REPORT, StateGraph.END);

        // Phase 2+ nodes will be added here:
        // .addNode(SqlAgentSpec.Node.SCHEMA_LINKING, AsyncNodeAction.node_async(schemaLinkingNode))
        // .addEdge(SqlAgentSpec.Node.EVIDENCE_RECALL, SqlAgentSpec.Node.SCHEMA_LINKING)
        // .addConditionalEdges(SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT, ...)
        // etc.
    }

    /**
     * MemorySaver for graph state checkpointing.
     * Required for HITL (interrupt-before) pattern in Phase 3+.
     */
    @Bean
    public MemorySaver sqlAgentMemorySaver() {
        return new MemorySaver();
    }
}