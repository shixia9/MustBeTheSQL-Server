package com.sql.logic.engine.infrastructure.health;

import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports LLM provider reachability via a minimal ping to the resolved strategy.
 * Fails gracefully — an unreachable LLM degrades the health status to DOWN
 * but never throws.
 */
@Component
public class LlmHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(LlmHealthIndicator.class);

    private final LlmClientManager llmClientManager;

    public LlmHealthIndicator(LlmClientManager llmClientManager) {
        this.llmClientManager = llmClientManager;
    }

    @Override
    public Health health() {
        try {
            // Try a minimal ping through the default strategy
            var strategy = llmClientManager.resolveStrategy(1L, null);
            if (strategy == null) {
                return Health.down().withDetail("reason", "No LLM strategy available").build();
            }
            String response = strategy.generateSql("Reply with: OK", null);
            if (response != null && !response.isBlank()) {
                return Health.up().withDetail("provider", "reachable").build();
            }
            return Health.down().withDetail("reason", "Empty LLM response").build();
        } catch (Exception e) {
            log.warn("[LlmHealthIndicator] LLM health check failed: {}", e.getMessage());
            return Health.down().withDetail("error", e.getMessage()).build();
        }
    }
}
