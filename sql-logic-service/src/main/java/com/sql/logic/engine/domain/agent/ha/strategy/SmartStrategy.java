package com.sql.logic.engine.domain.agent.ha.strategy;

import com.sql.logic.engine.domain.agent.ha.CandidateInstance;
import com.sql.logic.engine.domain.agent.ha.HaConstants;
import com.sql.logic.engine.domain.agent.ha.MetricsSnapshot;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SmartStrategy implements LoadBalancingStrategy {

    @Override
    public LLMStrategy selectInstance(List<CandidateInstance> candidates, MetricsSnapshot metrics) {
        List<CandidateInstance> available = candidates.stream()
                .filter(c -> !c.isCircuitBreakerOpen())
                .toList();
        if (available.isEmpty()) {
            return candidates.get(0).getStrategy();
        }
        if (available.size() == 1) {
            return available.get(0).getStrategy();
        }

        CandidateInstance best = null;
        double bestScore = -1;

        for (CandidateInstance c : available) {
            MetricsSnapshot.InstanceMetrics m = metrics.get(c.getConfigId());
            if (m == null || m.getTotalCount() < HaConstants.CIRCUIT_BREAKER_MIN_REQUEST_COUNT) {
                continue;
            }
            double successRate = m.getSuccessRate();
            long avgLatency = m.getAverageLatencyMs();
            double latencyScore = avgLatency > 0 ? 1.0 / (1.0 + avgLatency / 1000.0) : 1.0;
            double score = HaConstants.SMART_WEIGHT_SUCCESS_RATE * successRate
                    + HaConstants.SMART_WEIGHT_LATENCY * latencyScore;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best != null) {
            return best.getStrategy();
        }
        return available.get(0).getStrategy();
    }

    @Override
    public String getStrategyName() {
        return "SMART";
    }
}
