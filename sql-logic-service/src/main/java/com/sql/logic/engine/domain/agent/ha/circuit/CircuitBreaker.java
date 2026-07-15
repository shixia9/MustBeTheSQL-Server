package com.sql.logic.engine.domain.agent.ha.circuit;

import com.sql.logic.engine.domain.agent.ha.HaConstants;
import com.sql.logic.engine.infrastructure.dao.UserLlmConfigDao;
import com.sql.logic.engine.infrastructure.po.UserLlmConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final UserLlmConfigDao userLlmConfigDao;
    private final ConcurrentHashMap<Long, CircuitState> stateCache = new ConcurrentHashMap<>();

    public CircuitBreaker(UserLlmConfigDao userLlmConfigDao) {
        this.userLlmConfigDao = userLlmConfigDao;
    }

    public boolean isOpen(Long configId) {
        if (configId == null || configId == 0) {
            return false;
        }
        CircuitState state = stateCache.computeIfAbsent(configId, this::loadState);
        if (state == CircuitState.OPEN) {
            UserLlmConfig config = userLlmConfigDao.selectById(configId);
            if (config != null && config.getCircuitOpenedAt() != null) {
                long elapsed = System.currentTimeMillis() - config.getCircuitOpenedAt().getTime();
                if (elapsed > HaConstants.CIRCUIT_BREAKER_COOLDOWN_SECONDS * 1000L) {
                    stateCache.put(configId, CircuitState.HALF_OPEN);
                    updateDbState(configId, CircuitState.HALF_OPEN);
                    log.info("[CircuitBreaker] configId={} transitioned OPEN→HALF_OPEN after {}s cooldown",
                            configId, elapsed / 1000);
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public void reportSuccess(Long configId) {
        if (configId == null || configId == 0) return;
        CircuitState current = stateCache.get(configId);
        if (current == CircuitState.HALF_OPEN || current == CircuitState.OPEN) {
            stateCache.put(configId, CircuitState.CLOSED);
            updateDbState(configId, CircuitState.CLOSED);
            log.info("[CircuitBreaker] configId={} CLOSED after successful HALF_OPEN probe", configId);
        }
        UserLlmConfig config = userLlmConfigDao.selectById(configId);
        if (config != null) {
            config.setConsecutiveFailures(0);
            config.setLastSuccessAt(new Date());
            userLlmConfigDao.updateById(config);
        }
    }

    public void reportFailure(Long configId) {
        if (configId == null || configId == 0) return;
        UserLlmConfig config = userLlmConfigDao.selectById(configId);
        if (config == null) return;

        int failures = (config.getConsecutiveFailures() == null ? 0 : config.getConsecutiveFailures()) + 1;
        config.setConsecutiveFailures(failures);
        config.setLastFailureAt(new Date());
        userLlmConfigDao.updateById(config);

        CircuitState currentState = stateCache.getOrDefault(configId, CircuitState.CLOSED);
        switch (currentState) {
            case CLOSED:
                if (failures >= HaConstants.CIRCUIT_BREAKER_CONSECUTIVE_FAILURE_THRESHOLD) {
                    open(configId, config);
                }
                break;
            case HALF_OPEN:
                open(configId, config);
                break;
            case OPEN:
                break;
        }
    }

    private void open(Long configId, UserLlmConfig config) {
        stateCache.put(configId, CircuitState.OPEN);
        config.setCircuitState("OPEN");
        config.setCircuitOpenedAt(new Date());
        userLlmConfigDao.updateById(config);
        log.warn("[CircuitBreaker] configId={} OPEN — {} consecutive failures", configId,
                config.getConsecutiveFailures());
    }

    private CircuitState loadState(Long configId) {
        UserLlmConfig config = userLlmConfigDao.selectById(configId);
        if (config == null || config.getCircuitState() == null) {
            return CircuitState.CLOSED;
        }
        try {
            return CircuitState.valueOf(config.getCircuitState());
        } catch (IllegalArgumentException e) {
            return CircuitState.CLOSED;
        }
    }

    private void updateDbState(Long configId, CircuitState state) {
        UserLlmConfig config = userLlmConfigDao.selectById(configId);
        if (config != null) {
            config.setCircuitState(state.name());
            if (state != CircuitState.OPEN) {
                config.setCircuitOpenedAt(null);
            }
            userLlmConfigDao.updateById(config);
        }
    }
}
