package com.sql.logic.engine.domain.agentic.team;

import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import com.sql.logic.engine.domain.agentic.core.ConversableAgent;
import com.sql.logic.engine.domain.agentic.plan.PlanMemory;
import com.sql.logic.engine.domain.agentic.plan.PlanStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Team management mixin with Phase 4 LLM-based speaker selection.
 * <p>
 * Provides methods to hire worker agents, select the next speaker (with
 * LLM auto-selection fallback), and resolve dependency messages for
 * context injection.
 */
public interface TeamMixin {
    Logger log = LoggerFactory.getLogger(TeamMixin.class);

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
     * Select the speaker for a plan step (Phase 2 default: pre-assigned or first fallback).
     */
    default Agent selectSpeaker(PlanStep plan) {
        if (plan.getAgent() != null && !plan.getAgent().isBlank()) {
            Agent assigned = agentByName(plan.getAgent());
            if (assigned != null) return assigned;
        }
        List<Agent> agents = getAgents();
        return agents.isEmpty() ? null : agents.get(0);
    }

    /**
     * Phase 4 enhanced: select speaker with LLM-based auto-selection fallback.
     * <p>
     * When the plan step has no pre-assigned agent (or the agent is not found),
     * this method uses an LLM to select the most appropriate agent from the
     * available team based on the task description.
     *
     * @param plan        the plan step to assign
     * @param taskContext additional context about the overall task
     * @return the selected agent, or first available fallback
     */
    default Agent selectSpeaker(PlanStep plan, String taskContext) {
        // Direct assignment still takes priority
        if (plan.getAgent() != null && !plan.getAgent().isBlank()) {
            Agent assigned = agentByName(plan.getAgent());
            if (assigned != null) {
                return assigned;
            }
        }

        // Phase 4: LLM-based auto-selection
        List<Agent> agents = getAgents();
        if (agents.isEmpty()) return null;
        if (agents.size() == 1) return agents.get(0);

        Agent selected = autoSelectSpeaker(plan.getContent(), taskContext);
        return selected != null ? selected : agents.get(0);
    }

    /**
     * Use LLM to select the most appropriate agent for a task.
     * <p>
     * Builds a prompt describing available agents and the task, then parses
     * the LLM response to find the best match. Falls back to first agent on
     * any error.
     */
    default Agent autoSelectSpeaker(String taskContent, String taskContext) {
        List<Agent> agents = getAgents();
        if (agents.isEmpty()) return null;

        try {
            // Find any agent that can call LLM (typically the first ConversableAgent)
            ConversableAgent llmCapable = null;
            for (Agent a : agents) {
                if (a instanceof ConversableAgent ca) {
                    llmCapable = ca;
                    break;
                }
            }
            if (llmCapable == null) return agents.get(0);

            var strategy = llmCapable.resolveLlmStrategy();
            if (strategy == null) return agents.get(0);

            String agentDescriptions = agents.stream()
                    .map(a -> "- " + a.name() + " (" + a.role() + "): " + a.goal())
                    .collect(Collectors.joining("\n"));

            String prompt = """
                    Read the following task and select the most appropriate agent role.
                    Available agents:
                    %s

                    Task: %s
                    Context: %s

                    Reply with ONLY the agent name (one word).""".formatted(
                    agentDescriptions, taskContent,
                    taskContext != null ? taskContext : "N/A");

            String response = strategy.chat(prompt);
            if (response != null) {
                String trimmed = response.trim();
                // Try exact match first, then case-insensitive
                for (Agent a : agents) {
                    if (trimmed.equalsIgnoreCase(a.name())
                            || trimmed.contains(a.name())) {
                        return a;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("LLM speaker selection failed, using fallback: {}", e.getMessage());
        }
        return agents.get(0);
    }

    /**
     * Build rely messages from completed dependency steps.
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
