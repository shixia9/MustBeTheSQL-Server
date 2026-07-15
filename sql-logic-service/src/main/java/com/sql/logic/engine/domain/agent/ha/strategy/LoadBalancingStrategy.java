package com.sql.logic.engine.domain.agent.ha.strategy;

import com.sql.logic.engine.domain.agent.ha.CandidateInstance;
import com.sql.logic.engine.domain.agent.ha.MetricsSnapshot;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;

import java.util.List;

public interface LoadBalancingStrategy {

    LLMStrategy selectInstance(List<CandidateInstance> candidates, MetricsSnapshot metrics);

    String getStrategyName();
}
