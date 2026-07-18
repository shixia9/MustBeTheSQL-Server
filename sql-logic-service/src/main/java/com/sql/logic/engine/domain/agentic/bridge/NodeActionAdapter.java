package com.sql.logic.engine.domain.agentic.bridge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agentic.core.ConversableAgent;

import java.util.Map;

/**
 * Wraps a {@link ConversableAgent} as a Spring AI Alibaba {@link NodeAction},
 * enabling it to be registered as a node in any existing or future
 * {@code StateGraph}.
 * <p>
 * Usage in {@code SqlAgentGraphConfiguration}:
 * <pre>{@code
 *   DataScientistAgent dataScientist = ...;
 *   workflow.addNode("DATA_SCIENTIST",
 *       NodeActionAdapter.adapt(dataScientist));
 * }</pre>
 * <p>
 * The agent's {@link ConversableAgent#asNodeAction()} method already does this,
 * so this adapter is an alternative entry point when you prefer a static factory
 * style over calling the agent method directly.
 */
public final class NodeActionAdapter {

    private NodeActionAdapter() {}

    /**
     * Adapt a ConversableAgent to a NodeAction.
     */
    public static NodeAction adapt(ConversableAgent agent) {
        return agent.asNodeAction();
    }

    /**
     * Adapt a ConversableAgent with a custom output key for state updates.
     * <p>
     * By default, the agent writes its output to the standard
     * {@code SQL_GENERATION_RESULT} and {@code SQL_EXECUTION_RESULT} state keys.
     * Use this overload to redirect output to a custom key prefix.
     *
     * @param agent       the agent to adapt
     * @param outputKey   custom state key for the primary output
     * @param errorKey    custom state key for error output
     */
    public static NodeAction adapt(ConversableAgent agent, String outputKey, String errorKey) {
        return (OverAllState state) -> {
            com.sql.logic.engine.domain.agentic.core.AgentMessage input =
                    AgentStateBridge.toAgentMessage(state);
            com.sql.logic.engine.domain.agentic.core.AgentMessage output =
                    agent.generateReply(input, null, null, null).join();
            Map<String, Object> updates = AgentStateBridge.toStateUpdates(output);
            // Override with custom keys
            updates.put(outputKey, output.content());
            if (output.actionReport() != null && !output.actionReport().success()) {
                updates.put(errorKey, output.actionReport().content());
            }
            return updates;
        };
    }
}
