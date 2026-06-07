package com.sql.logic.engine.domain.agent.core;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AiAgentManager {

    // Cache userId -> AiAgent
    // userId 0 is reserved for System Default Agent
    private final ConcurrentHashMap<Long, AiAgent> agentCache = new ConcurrentHashMap<>();

    public void registerAgent(Long userId, AiAgent agent) {
        agentCache.put(userId, agent);
        System.out.println("[AiAgentManager] Successfully registered AI Agent for user: " + userId);
    }

    public AiAgent getAgent(Long userId) {
        AiAgent agent = agentCache.get(userId);
        if (agent == null) {
            // Fallback to system default agent (userId = 0)
            return agentCache.get(0L);
        }
        return agent;
    }

    public void removeAgent(Long userId) {
        agentCache.remove(userId);
        System.out.println("[AiAgentManager] Removed AI Agent for user: " + userId);
    }
}