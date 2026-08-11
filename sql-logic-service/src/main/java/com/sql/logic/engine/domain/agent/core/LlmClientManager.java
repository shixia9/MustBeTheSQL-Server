package com.sql.logic.engine.domain.agent.core;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.ha.AffinityStore;
import com.sql.logic.engine.domain.agent.ha.CandidateInstance;
import com.sql.logic.engine.domain.agent.ha.FailoverLLMStrategy;
import com.sql.logic.engine.domain.agent.ha.HaConstants;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.ha.MetricsSnapshot;
import com.sql.logic.engine.domain.agent.ha.circuit.CircuitBreaker;
import com.sql.logic.engine.domain.agent.ha.strategy.LoadBalancingStrategy;
import com.sql.logic.engine.domain.agent.ha.strategy.LoadBalancingStrategyFactory;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.domain.trace.TracingLlmClientWrapper;
import com.sql.logic.engine.infrastructure.dao.LlmCallMetricsDao;
import com.sql.logic.engine.infrastructure.dao.UserLlmConfigDao;
import com.sql.logic.engine.infrastructure.po.LlmCallMetrics;
import com.sql.logic.engine.infrastructure.po.UserLlmConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages LLMStrategy instances keyed by configId.
 *
 * Each UserLlmConfig corresponds to one LLMStrategy (which wraps a ChatClient
 * with its own connection pool and credentials). These are the true "heavy resources"
 * that need to be pooled and reused across requests.
 *
 * The system default strategy is stored under key 0L.
 */
@Component
public class LlmClientManager {

    private static final Logger log = LoggerFactory.getLogger(LlmClientManager.class);

    private final ConcurrentHashMap<Long, LLMStrategy> clientCache = new ConcurrentHashMap<>();
    private final UserLlmConfigDao userLlmConfigDao;
    private final LlmCallMetricsDao llmCallMetricsDao;
    private final LoadBalancingStrategyFactory strategyFactory;
    private final CircuitBreaker circuitBreaker;
    private final AffinityStore affinityStore;
    private final ObjectMapper objectMapper;

    public LlmClientManager(UserLlmConfigDao userLlmConfigDao,
                            LlmCallMetricsDao llmCallMetricsDao,
                            LoadBalancingStrategyFactory strategyFactory,
                            CircuitBreaker circuitBreaker,
                            AffinityStore affinityStore,
                            ObjectMapper objectMapper) {
        this.userLlmConfigDao = userLlmConfigDao;
        this.llmCallMetricsDao = llmCallMetricsDao;
        this.strategyFactory = strategyFactory;
        this.circuitBreaker = circuitBreaker;
        this.affinityStore = affinityStore;
        this.objectMapper = objectMapper;
    }

    /**
     * Register a LLMStrategy for a given configId.
     */
    public void registerClient(Long configId, LLMStrategy strategy) {
        clientCache.put(configId, strategy);
        log.info("[LlmClientManager] Registered LLM client for config: {}", configId);
    }

    /**
     * Get a LLMStrategy by configId.
     * Falls back to system default (key 0) if the specific config is not found.
     */
    public LLMStrategy getClient(Long configId) {
        if (configId != null && configId > 0) {
            LLMStrategy strategy = clientCache.get(configId);
            if (strategy != null) {
                return strategy;
            }
        }
        // Fallback to system default
        return clientCache.get(0L);
    }

    /**
     * Return any available LLM strategy, preferring system default (0L) first,
     * then any registered strategy. Returns null only if the cache is completely empty.
     */
    public LLMStrategy getAnyClient() {
        LLMStrategy s = clientCache.get(0L);
        if (s != null) return s;
        // Pick any registered strategy as last resort
        for (LLMStrategy v : clientCache.values()) {
            if (v != null) return v;
        }
        return null;
    }

    /**
     * Resolve the best LLM strategy for a request.
     * <p>
     * Resolution order:
     * 1. If llmConfigId is explicitly provided and found, use it.
     * 2. If userId is provided, look up the user's default active config.
     * 3. Fall back to system default (key 0).
     * <p>
     * This matches the behavior of SQLGenerateAppService for non-agent flows.
     */
    public LLMStrategy resolveStrategy(Long llmConfigId, Long userId) {
        // Explicit system default
        if (llmConfigId != null && llmConfigId == 0) {
            return clientCache.get(0L);
        }
        // 1. Explicit config
        if (llmConfigId != null && llmConfigId > 0) {
            LLMStrategy strategy = clientCache.get(llmConfigId);
            if (strategy != null) {
                return strategy;
            }
        }
        // 2. User default
        if (userId != null && userId > 0) {
            LLMStrategy userDefault = getDefaultForUser(userId);
            if (userDefault != null) {
                return userDefault;
            }
        }
        // 3. System default
        return clientCache.get(0L);
    }

    /**
     * Get the default LLMStrategy for a user.
     * Looks up the user's isDefault config first, then falls back to any active config,
     * then to system default.
     */
    public LLMStrategy getDefaultForUser(Long userId) {
        // Try to find the user's default config
        QueryWrapper<UserLlmConfig> defaultQuery = new QueryWrapper<>();
        defaultQuery.eq("user_id", userId).eq("is_default", 1).eq("status", 1);
        UserLlmConfig defaultConfig = userLlmConfigDao.selectOne(defaultQuery);

        if (defaultConfig != null) {
            LLMStrategy strategy = clientCache.get(defaultConfig.getId());
            if (strategy != null) {
                return strategy;
            }
        }

        // Try any active config for this user
        QueryWrapper<UserLlmConfig> anyQuery = new QueryWrapper<>();
        anyQuery.eq("user_id", userId).eq("status", 1);
        List<UserLlmConfig> configs = userLlmConfigDao.selectList(anyQuery);
        for (UserLlmConfig config : configs) {
            LLMStrategy strategy = clientCache.get(config.getId());
            if (strategy != null) {
                return strategy;
            }
        }

        // System default
        return clientCache.get(0L);
    }

    /**
     * Remove a LLMStrategy by configId.
     */
    public void removeClient(Long configId) {
        clientCache.remove(configId);
        log.info("[LlmClientManager] Removed LLM client for config: {}", configId);
    }

    /**
     * Remove all LLMStrategy instances for a given user.
     */
    public void removeClientsForUser(Long userId) {
        QueryWrapper<UserLlmConfig> query = new QueryWrapper<>();
        query.eq("user_id", userId);
        List<UserLlmConfig> configs = userLlmConfigDao.selectList(query);
        for (UserLlmConfig config : configs) {
            clientCache.remove(config.getId());
        }
        log.info("[LlmClientManager] Removed all LLM clients for user: {}", userId);
    }

    /**
     * Check if a user has any active custom LLM config.
     */
    public boolean hasActiveConfig(Long userId) {
        QueryWrapper<UserLlmConfig> query = new QueryWrapper<>();
        query.eq("user_id", userId).eq("status", 1);
        return userLlmConfigDao.selectCount(query) > 0;
    }

    /**
     * send a minimal non-streaming prompt to the resolved LLM strategy
     * to verify connectivity. Returns a short acknowledgement string on success
     * or throws on failure. Used by the LLM config "Test Connection" feature.
     */
    public String testPing(Long configId, Long userId) {
        LLMStrategy strategy = resolveStrategy(configId, userId);
        if (strategy == null) {
            throw new IllegalStateException("No LLM strategy available for config " + configId);
        }
        String result = strategy.generateSql("Reply with: OK", (tokens, content) -> { /* ignore token accounting */ });
        return result != null && !result.isBlank() ? result.trim() : "OK";
    }

    // public LLMStrategy resolveTraced(Long llmConfigId, Long userId, TraceContext traceContext,
    //                                   String nodeName, LlmCallReporter reporter) {
    //     LLMStrategy raw = resolveStrategy(llmConfigId, userId);
    //     Long effectiveConfigId = resolveEffectiveConfigId(llmConfigId, userId);
    //     return new TracingLlmClientWrapper(raw, traceContext, nodeName, reporter,
    //             effectiveConfigId, userId);
    // }

    public LLMStrategy resolveTraced(Long llmConfigId, Long userId, TraceContext traceContext,
                                  String nodeName, LlmCallReporter reporter) {
    return resolveWithStrategy(llmConfigId, userId, null, traceContext, nodeName, reporter);
}

    private Long resolveEffectiveConfigId(Long llmConfigId, Long userId) {
        if (llmConfigId != null && llmConfigId == 0) {
            return 0L;
        }
        if (llmConfigId != null && llmConfigId > 0 && clientCache.containsKey(llmConfigId)) {
            return llmConfigId;
        }
        if (userId != null && userId > 0) {
            QueryWrapper<UserLlmConfig> defaultQuery = new QueryWrapper<>();
            defaultQuery.eq("user_id", userId).eq("is_default", 1).eq("status", 1);
            UserLlmConfig defaultConfig = userLlmConfigDao.selectOne(defaultQuery);
            if (defaultConfig != null && clientCache.containsKey(defaultConfig.getId())) {
                return defaultConfig.getId();
            }
            QueryWrapper<UserLlmConfig> anyQuery = new QueryWrapper<>();
            anyQuery.eq("user_id", userId).eq("status", 1);
            List<UserLlmConfig> configs = userLlmConfigDao.selectList(anyQuery);
            for (UserLlmConfig config : configs) {
                if (clientCache.containsKey(config.getId())) {
                    return config.getId();
                }
            }
        }
        return 0L;
    }

    public LLMStrategy resolveWithStrategy(Long primaryConfigId, Long userId, String sessionId,
                                            TraceContext traceContext, String nodeName,
                                            LlmCallReporter reporter) {
        Long effectiveConfigId = resolveEffectiveConfigId(primaryConfigId, userId);
        UserLlmConfig config = userLlmConfigDao.selectById(effectiveConfigId);

        List<Long> candidateIds = new ArrayList<>();
        candidateIds.add(effectiveConfigId);
        if (config != null && config.getFallbackChain() != null && !config.getFallbackChain().isBlank()) {
            try {
                List<Integer> fallbackIds = objectMapper.readValue(config.getFallbackChain(),
                        new TypeReference<List<Integer>>() {});
                for (Integer id : fallbackIds) {
                    if (id != null && id > 0 && !candidateIds.contains(id.longValue())) {
                        candidateIds.add(id.longValue());
                    }
                }
            } catch (Exception e) {
                log.warn("[LlmClientManager] Failed to parse fallback chain for configId={}: {}",
                        effectiveConfigId, e.getMessage());
            }
        }

        if (sessionId != null) {
            Long bound = affinityStore.getBinding(sessionId);
            if (bound != null && candidateIds.contains(bound) && !circuitBreaker.isOpen(bound)) {
                effectiveConfigId = bound;
            }
        }

        String strategyType = config != null ? config.getStrategyType() : null;
        boolean isLocal = strategyType == null || strategyType.isBlank()
                || "LOCAL".equalsIgnoreCase(strategyType);

        if (isLocal) {
            // LOCAL: ordered failover across all healthy candidates.
            // If only one healthy candidate exists, behaviour is unchanged
            // (a single TracingLlmClientWrapper). When the fallback chain
            // offers additional healthy candidates, wrap them in a
            // FailoverLLMStrategy so runtime failures of the primary
            // transparently fall back within a single request.
            List<Long> healthy = findAllHealthy(candidateIds);
            if (healthy.isEmpty()) {
                effectiveConfigId = candidateIds.isEmpty() ? 0L : candidateIds.get(0);
                LLMStrategy raw = getClient(effectiveConfigId);
                if (sessionId != null) {
                    affinityStore.bind(sessionId, effectiveConfigId);
                }
                return new TracingLlmClientWrapper(raw, traceContext, nodeName, reporter,
                        effectiveConfigId, userId);
            }
            if (healthy.size() == 1) {
                effectiveConfigId = healthy.get(0);
                LLMStrategy raw = getClient(effectiveConfigId);
                if (sessionId != null) {
                    affinityStore.bind(sessionId, effectiveConfigId);
                }
                return new TracingLlmClientWrapper(raw, traceContext, nodeName, reporter,
                        effectiveConfigId, userId);
            }
            effectiveConfigId = healthy.get(0);
            if (sessionId != null) {
                affinityStore.bind(sessionId, effectiveConfigId);
            }
            return new FailoverLLMStrategy(healthy, this, traceContext, nodeName, reporter, userId);
        }

        LoadBalancingStrategy lbStrategy = strategyFactory.getSmartStrategy(strategyType);
        List<CandidateInstance> candidates = buildCandidates(candidateIds);
        MetricsSnapshot snapshot = loadMetricsSnapshot(candidateIds);

        LLMStrategy selected = lbStrategy.selectInstance(candidates, snapshot);

        Long lbSelectedId = effectiveConfigId;
        for (CandidateInstance c : candidates) {
            if (c.getStrategy() == selected) {
                lbSelectedId = c.getConfigId();
                break;
            }
        }
        effectiveConfigId = lbSelectedId;

        if (sessionId != null) {
            affinityStore.bind(sessionId, effectiveConfigId);
        }

        // Build an ordered failover list: LB-selected primary first,
        // followed by the remaining healthy candidates as fallbacks.
        List<Long> ordered = new ArrayList<>();
        ordered.add(effectiveConfigId);
        for (Long id : candidateIds) {
            if (id.equals(effectiveConfigId)) continue;
            if (circuitBreaker.isOpen(id) || !clientCache.containsKey(id)) continue;
            if (!ordered.contains(id)) {
                ordered.add(id);
            }
        }
        if (ordered.size() == 1) {
            return new TracingLlmClientWrapper(selected, traceContext, nodeName, reporter,
                    effectiveConfigId, userId);
        }
        return new FailoverLLMStrategy(ordered, this, traceContext, nodeName, reporter, userId);
    }

    private Long findFirstHealthy(List<Long> candidateIds) {
        for (Long id : candidateIds) {
            if (!circuitBreaker.isOpen(id) && clientCache.containsKey(id)) {
                return id;
            }
        }
        return candidateIds.isEmpty() ? 0L : candidateIds.get(0);
    }

    /**
     * Return every healthy candidate (not circuit-broken and registered),
     * preserving the input order so the primary is always tried first.
     */
    private List<Long> findAllHealthy(List<Long> candidateIds) {
        List<Long> healthy = new ArrayList<>();
        for (Long id : candidateIds) {
            if (id != null && id > 0 && !circuitBreaker.isOpen(id) && clientCache.containsKey(id)
                    && !healthy.contains(id)) {
                healthy.add(id);
            }
        }
        return healthy;
    }

    private List<CandidateInstance> buildCandidates(List<Long> configIds) {
        List<CandidateInstance> list = new ArrayList<>();
        MetricsSnapshot snapshot = loadMetricsSnapshot(configIds);
        for (Long id : configIds) {
            LLMStrategy strategy = clientCache.get(id);
            if (strategy == null) continue;
            MetricsSnapshot.InstanceMetrics m = snapshot.get(id);
            double successRate = m != null ? m.getSuccessRate() : 1.0;
            long avgLatency = m != null ? m.getAverageLatencyMs() : 0L;
            int consecutiveFailures = 0;
            UserLlmConfig config = userLlmConfigDao.selectById(id);
            if (config != null && config.getConsecutiveFailures() != null) {
                consecutiveFailures = config.getConsecutiveFailures();
            }
            boolean breakerOpen = circuitBreaker.isOpen(id);
            list.add(new CandidateInstance(id, strategy, successRate, avgLatency,
                    consecutiveFailures, breakerOpen));
        }
        return list;
    }

    private MetricsSnapshot loadMetricsSnapshot(List<Long> configIds) {
        LocalDateTime since = LocalDateTime.now()
                .minusMinutes(HaConstants.METRICS_WINDOW_MINUTES)
                .truncatedTo(ChronoUnit.MINUTES);

        Map<Long, MetricsSnapshot.InstanceMetrics> map = new LinkedHashMap<>();
        for (Long id : configIds) {
            QueryWrapper<LlmCallMetrics> wrapper = new QueryWrapper<>();
            wrapper.eq("config_id", id);
            wrapper.ge("window_start", since);
            List<LlmCallMetrics> rows = llmCallMetricsDao.selectList(wrapper);

            int success = 0, failure = 0;
            long totalLatency = 0L;
            for (LlmCallMetrics row : rows) {
                success += row.getSuccessCount() != null ? row.getSuccessCount() : 0;
                failure += row.getFailureCount() != null ? row.getFailureCount() : 0;
                totalLatency += row.getTotalLatencyMs() != null ? row.getTotalLatencyMs() : 0L;
            }
            map.put(id, new MetricsSnapshot.InstanceMetrics(success, failure, totalLatency));
        }
        return new MetricsSnapshot(map);
    }
}