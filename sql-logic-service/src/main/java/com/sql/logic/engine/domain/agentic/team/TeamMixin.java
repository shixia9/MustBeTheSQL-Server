package com.sql.logic.engine.domain.agentic.team;

import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanStep;

import java.util.ArrayList;
import java.util.List;

/**
 * Team management mixin.
 * <p>
 * Provides methods to hire worker agents, select the next speaker based on
 * plan step assignments, and resolve dependency messages for context injection.
 * <p>
 * Implemented as a Java interface with default methods so that
 * {@code ManagerAgent} (which already extends {@code ConversableAgent})
 * can compose team behaviors without multi-inheritance.
 */
public interface TeamMixin {

    List<Agent> getAgents();
    PlanMemory getPlanMemory();

    /**
     * Register a worker agent into the team.
     */
    default void hire(Agent agent) {
        getAgents().add(agent);
    }

    /**
     * Register multiple worker agents.
     */
    default void hireAll(List<Agent> agents) {
        getAgents().addAll(agents);
    }

    /**
     * Find an agent by its profile name.
     */
    default Agent agentByName(String name) {
        if (name == null || name.isBlank()) return null;
        return getAgents().stream()
                .filter(a -> name.equalsIgnoreCase(a.name()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Select the speaker for a plan step.
     * If the plan step specifies an agent, use it directly.
     * Otherwise, return the first available agent (Phase 2 default;
     * LLM-based auto-selection can be added in Phase 4).
     */
    default Agent selectSpeaker(PlanStep plan) {
        if (plan.getAgent() != null && !plan.getAgent().isBlank()) {
            Agent assigned = agentByName(plan.getAgent());
            if (assigned != null) return assigned;
        }
        // Fallback: return first available agent
        List<Agent> agents = getAgents();
        return agents.isEmpty() ? null : agents.get(0);
    }

    /**
     * Build rely messages from completed dependency steps.
     * For each dependency step number in the plan's {@code rely} field,
     * fetch the completed step's content and result and format them
     * as a question/observation pair for the worker Agent's context.
     */
    default List<AgentMessage> processRelyMessages(String convId, PlanStep plan) {
        List<AgentMessage> relyMessages = new ArrayList<>();
        if (plan.getRely() == null || plan.getRely().isBlank()) return relyMessages;

        String[] parts = plan.getRely().split(",");
        List<Integer> relyNums = new ArrayList<>();
        for (String p : parts) {
            try {
                relyNums.add(Integer.parseInt(p.trim()));
            } catch (NumberFormatException ignored) {}
        }
        if (relyNums.isEmpty()) return relyMessages;

        List<PlanStep> relySteps = getPlanMemory().getByConvIdAndNum(convId, relyNums);
        for (PlanStep step : relySteps) {
            relyMessages.add(AgentMessage.builder()
                    .content("Question: " + step.getContent())
                    .messageType(AgentMessage.MessageType.USER)
                    .build());
            relyMessages.add(AgentMessage.builder()
                    .content("Observation: " + (step.getResult() != null ? step.getResult() : ""))
                    .messageType(AgentMessage.MessageType.AI)
                    .build());
        }
        return relyMessages;
    }
}
