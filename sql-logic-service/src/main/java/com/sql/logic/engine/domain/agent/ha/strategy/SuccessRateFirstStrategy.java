package com.sql.logic.engine.domain.agent.ha.strategy;

import com.sql.logic.engine.domain.agent.ha.CandidateInstance;
import com.sql.logic.engine.domain.agent.ha.MetricsSnapshot;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class SuccessRateFirstStrategy implements LoadBalancingStrategy {

    @Override
    public LLMStrategy selectInstance(List<CandidateInstance> candidates, MetricsSnapshot metrics) {
        return candidates.stream()
                .filter(c -> !c.isCircuitBreakerOpen())
                .max(Comparator.comparingDouble(CandidateInstance::getSuccessRate))
                .orElse(candidates.get(0))
                .getStrategy();
    }

    @Override
    public String getStrategyName() {
        return "SUCCESS_RATE_FIRST";
    }
}
