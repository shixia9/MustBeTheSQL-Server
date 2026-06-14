package com.sql.logic.engine.domain.agent.core;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages AiAgent instances keyed by userId.
 *
 * Each user has at most ONE agent (a prompt builder). The agent itself
 * is generic — it receives an LLMStrategy at call time based on which
 * LLM config the user selected.
 *
 * The system default agent (userId=0) is created at startup using the
 * admin-provided OpenAI key.
 */
@Component
public class AiAgentManager {

    private final ConcurrentHashMap<Long, AiAgent> agentCache = new ConcurrentHashMap<>();

    /**
     * Register an agent for a user.
     */
    public void registerAgent(Long userId, AiAgent agent) {
        agentCache.put(userId, agent);
        System.out.println("[AiAgentManager] Successfully registered AI Agent for user: " + userId);
    }

    /**
     * Get agent for a user. Falls back to system default (userId=0) if not found.
     */
    public AiAgent getAgent(Long userId) {
        AiAgent agent = agentCache.get(userId);
        if (agent == null) {
            // Fallback to system default agent (userId = 0)
            return agentCache.get(0L);
        }
        return agent;
    }

    /**
     * Remove agent for a user.
     */
    public void removeAgent(Long userId) {
        agentCache.remove(userId);
        System.out.println("[AiAgentManager] Removed AI Agent for user: " + userId);
    }
}