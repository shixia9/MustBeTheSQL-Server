package com.sql.logic.engine.domain.agentic.config;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agentic.agent.*;
import com.sql.logic.engine.domain.agentic.hitl.AgenticHitlGateNode;
import com.sql.logic.engine.domain.agentic.hitl.AgenticHitlNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compiles the 6-Agent system into a streamlined StateGraph for execution.
 * Entry point is ManagerAgent, which orchestrates PlannerAgent → worker Agents
 * → DashboardAssistantAgent via a compact 6-node graph (vs. 18 in the old system).
 */
public class AgentOrchestrator {

    private final StateGraph stateGraph;
    private final CompiledGraph compiledGraph;

    public AgentOrchestrator(
            PlannerAgent planner,
            ManagerAgent manager,
            DataScientistAgent dataScientist,
            CodeAssistantAgent codeAssistant,
            DashboardAssistantAgent dashboard,
            ToolAssistantAgent toolAssistant,
            AgenticHitlGateNode hitlGateNode,
            AgenticHitlNode hitlNode) throws GraphStateException {

        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new LinkedHashMap<>();
            strategies.put(SqlAgentSpec.StateKey.INPUT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.NEXT_NODE, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SQL_ERROR, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.REPORT_RESULT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.PLAN, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.SCHEMA_DDL, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.AUTO_CONFIRM, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.NEEDS_HUMAN_REVIEW, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.CONFIRMATION_APPROVED, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.REPAIR_COUNT, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.HITL_GATE_EVALUATED, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.HITL_STATUS, new ReplaceStrategy());
            strategies.put(SqlAgentSpec.StateKey.HITL_VERSION, new ReplaceStrategy());
            return strategies;
        };

        StateGraph graph = new StateGraph("agentic-orchestrator", keyStrategyFactory);

        // 6 Agent nodes (wrapped as AsyncNodeAction)
        graph.addNode("PLANNER", AsyncNodeAction.node_async(planner.asNodeAction()));
        graph.addNode("MANAGER", AsyncNodeAction.node_async(manager.asNodeAction()));
        graph.addNode("DATA_SCIENTIST", AsyncNodeAction.node_async(dataScientist.asNodeAction()));
        graph.addNode("CODE_ASSISTANT", AsyncNodeAction.node_async(codeAssistant.asNodeAction()));
        graph.addNode("DASHBOARD", AsyncNodeAction.node_async(dashboard.asNodeAction()));
        graph.addNode("TOOL_ASSISTANT", AsyncNodeAction.node_async(toolAssistant.asNodeAction()));
        graph.addNode("HITL_GATE", AsyncNodeAction.node_async(hitlGateNode));
        graph.addNode("HITL", AsyncNodeAction.node_async(hitlNode));

        // Entry: Manager handles everything
        graph.addEdge(StateGraph.START, "MANAGER");

        // Manager routes based on nextNode (set by ManagerAgent.act() via state updates)
        graph.addConditionalEdges("MANAGER",
                AsyncEdgeAction.edge_async((OverAllState state) -> {
                    Object next = state.value(SqlAgentSpec.StateKey.NEXT_NODE).orElse(null);
                    if (next instanceof String s && !s.isBlank()) return s;
                    return StateGraph.END;
                }),
                Map.of(
                        "PLANNER", "PLANNER",
                        "DATA_SCIENTIST", "DATA_SCIENTIST",
                        "CODE_ASSISTANT", "CODE_ASSISTANT",
                        "TOOL_ASSISTANT", "TOOL_ASSISTANT",
                        "DASHBOARD", "DASHBOARD",
                        "HITL_GATE", "HITL_GATE",
                        "MANAGER", "MANAGER",
                        StateGraph.END, StateGraph.END
                ));

        graph.addConditionalEdges("HITL_GATE",
                AsyncEdgeAction.edge_async(state -> {
                    Object value = state.value(SqlAgentSpec.StateKey.NEEDS_HUMAN_REVIEW,
                            Boolean.FALSE);
                    boolean needsReview = value instanceof Boolean b
                            ? b : Boolean.parseBoolean(String.valueOf(value));
                    return needsReview ? "HITL" : "MANAGER";
                }),
                Map.of("HITL", "HITL", "MANAGER", "MANAGER"));

        graph.addConditionalEdges("HITL",
                AsyncEdgeAction.edge_async(state -> state.value(SqlAgentSpec.StateKey.NEXT_NODE,
                                StateGraph.END)),
                Map.of("MANAGER", "MANAGER", StateGraph.END, StateGraph.END));

        // Workers return to Manager for next step dispatch
        graph.addEdge("PLANNER", "MANAGER");
        graph.addEdge("DATA_SCIENTIST", "MANAGER");
        graph.addEdge("CODE_ASSISTANT", "MANAGER");
        graph.addEdge("TOOL_ASSISTANT", "MANAGER");
        graph.addEdge("DASHBOARD", StateGraph.END);

        this.stateGraph = graph;
        this.compiledGraph = graph.compile(CompileConfig.builder().build());
    }

    public CompiledGraph getCompiledGraph() {
        return compiledGraph;
    }

    /**
     * Compile the underlying StateGraph with a custom {@link CompileConfig}.
     * Enables callers (e.g. {@code AgenticRunner}) to attach lifecycle listeners,
     * saver config, etc. without duplicating graph construction.
     */
    public CompiledGraph compile(CompileConfig config) throws GraphStateException {
        return stateGraph.compile(config);
    }
}
