package com.sql.logic.engine.domain.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.application.service.AgentEntityAppService;
import com.sql.logic.engine.infrastructure.po.AgentEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase B (B4) bridge between the persisted {@code agent_entity} and the Agent runtime.
 * <p>
 * Resolves the user's default Agent into an immutable {@link AgentRuntimeConfig}, cached
 * per user and invalidated on any Studio mutation (create/update/delete/set-default) so
 * the next run picks up the new config without a restart.
 * <p>
 * Falls back to {@link AgentRuntimeConfig#defaults()} when the user has no Agent row or
 * parsing fails — the run always proceeds with sane behaviour.
 */
@Service
public class AgentRuntimeConfigService {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeConfigService.class);
    private static final List<String> ALL_TOOLS = List.of("sql", "schema", "python", "sample");

    private final AgentEntityAppService agentEntityAppService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<Long, AgentRuntimeConfig> cache = new ConcurrentHashMap<>();

    public AgentRuntimeConfigService(AgentEntityAppService agentEntityAppService, ObjectMapper objectMapper) {
        this.agentEntityAppService = agentEntityAppService;
        this.objectMapper = objectMapper;
    }

    /** Drop the cached config for a user — call after any Studio mutation. */
    public void invalidate(Long userId) {
        if (userId != null) cache.remove(userId);
    }

    /** Resolve the user's effective runtime config (default Agent, or defaults()). */
    public AgentRuntimeConfig resolve(Long userId) {
        if (userId == null) return AgentRuntimeConfig.defaults();
        return cache.computeIfAbsent(userId, this::load);
    }

    private AgentRuntimeConfig load(Long userId) {
        try {
            AgentEntity e = agentEntityAppService.findDefaultForUser(userId);
            if (e == null) return AgentRuntimeConfig.defaults();
            List<String> tools = parseTools(e.getToolsConfig());
            RagParams rag = parseRag(e.getRagConfig());
            boolean memoryEnabled = e.getMemoryEnabled() == null || e.getMemoryEnabled() == 1;
            String contextStrategy = rag.contextStrategy != null ? rag.contextStrategy : "TRUNCATE";
            return new AgentRuntimeConfig(e.getId(), e.getName(), e.getSystemPrompt(),
                    e.getWelcomeMessage(), tools, rag.topK, rag.scoreThreshold, rag.enabled, memoryEnabled, contextStrategy);
        } catch (Exception ex) {
            log.warn("[AgentRuntimeConfigService] load failed for userId={}: {}", userId, ex.getMessage());
            return AgentRuntimeConfig.defaults();
        }
    }

    private List<String> parseTools(String json) {
        List<String> enabled = new ArrayList<>();
        if (json == null || json.isBlank()) return new ArrayList<>(ALL_TOOLS);
        try {
            Map<?, ?> m = objectMapper.readValue(json, Map.class);
            for (String t : ALL_TOOLS) {
                Object v = m.get(t);
                if (Boolean.TRUE.equals(v)) enabled.add(t);
            }
        } catch (Exception ignored) { return new ArrayList<>(ALL_TOOLS); }
        return enabled;
    }

    private RagParams parseRag(String json) {
        if (json == null || json.isBlank()) return new RagParams(5, 0.6, true, null);
        try {
            Map<?, ?> m = objectMapper.readValue(json, Map.class);
            int topK = asInt(m.get("topK"), 5);
            double threshold = asDouble(m.get("scoreThreshold"), 0.6);
            boolean enabled = m.get("enabled") == null || Boolean.TRUE.equals(m.get("enabled"));
            String ctxStrategy = m.get("contextStrategy") instanceof String s ? s : null;
            return new RagParams(topK, threshold, enabled, ctxStrategy);
        } catch (Exception ignored) { return new RagParams(5, 0.6, true, null); }
    }

    private int asInt(Object o, int dflt) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? dflt : Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return dflt; }
    }

    private double asDouble(Object o, double dflt) {
        if (o instanceof Number n) return n.doubleValue();
        try { return o == null ? dflt : Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return dflt; }
    }

    private record RagParams(int topK, double scoreThreshold, boolean enabled, String contextStrategy) {}
}
