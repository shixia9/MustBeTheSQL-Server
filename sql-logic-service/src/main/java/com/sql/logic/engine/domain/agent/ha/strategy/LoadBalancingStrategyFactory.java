package com.sql.logic.engine.domain.agent.ha.strategy;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class LoadBalancingStrategyFactory {

    private final List<LoadBalancingStrategy> allStrategies;
    private final Map<String, LoadBalancingStrategy> strategyMap = new HashMap<>();

    public LoadBalancingStrategyFactory(List<LoadBalancingStrategy> allStrategies) {
        this.allStrategies = allStrategies;
    }

    @PostConstruct
    public void initStrategies() {
        for (LoadBalancingStrategy strategy : allStrategies) {
            strategyMap.put(strategy.getStrategyName(), strategy);
        }
    }

    public LoadBalancingStrategy getStrategy(String strategyType) {
        if (strategyType == null || strategyType.isBlank()) {
            return null;
        }
        return strategyMap.get(strategyType.toUpperCase());
    }

    public LoadBalancingStrategy getSmartStrategy(String strategyType) {
        LoadBalancingStrategy explicit = getStrategy(strategyType);
        if (explicit != null) {
            return explicit;
        }
        return strategyMap.get("SMART");
    }
}
