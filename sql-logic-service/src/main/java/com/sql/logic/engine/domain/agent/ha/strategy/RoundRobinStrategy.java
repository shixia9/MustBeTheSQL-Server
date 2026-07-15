package com.sql.logic.engine.domain.agent.ha.strategy;

import com.sql.logic.engine.domain.agent.ha.CandidateInstance;
import com.sql.logic.engine.domain.agent.ha.MetricsSnapshot;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RoundRobinStrategy implements LoadBalancingStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public LLMStrategy selectInstance(List<CandidateInstance> candidates, MetricsSnapshot metrics) {
        List<CandidateInstance> available = candidates.stream()
                .filter(c -> !c.isCircuitBreakerOpen())
                .toList();
        if (available.isEmpty()) {
            return candidates.get(0).getStrategy();
        }
        int idx = Math.abs(counter.getAndIncrement() % available.size());
        return available.get(idx).getStrategy();
    }

    @Override
    public String getStrategyName() {
        return "ROUND_ROBIN";
    }
}
