package com.sql.logic.engine.domain.agent.ha;

import com.sql.logic.engine.domain.agent.ha.circuit.CircuitBreaker;
import com.sql.logic.engine.infrastructure.dao.LlmCallMetricsDao;
import com.sql.logic.engine.infrastructure.po.LlmCallMetrics;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class DefaultLlmCallReporter implements LlmCallReporter {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmCallReporter.class);

    private final LlmCallMetricsDao llmCallMetricsDao;
    private final CircuitBreaker circuitBreaker;

    public DefaultLlmCallReporter(LlmCallMetricsDao llmCallMetricsDao, CircuitBreaker circuitBreaker) {
        this.llmCallMetricsDao = llmCallMetricsDao;
        this.circuitBreaker = circuitBreaker;
    }

    @Async
    @Override
    public void report(Long configId, Long userId, boolean success, long latencyMs, int inputTokens, int outputTokens) {
        try {
            LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES);

            QueryWrapper<LlmCallMetrics> wrapper = new QueryWrapper<>();
            wrapper.eq("config_id", configId);
            wrapper.eq("window_start", now);
            LlmCallMetrics metrics = llmCallMetricsDao.selectOne(wrapper);

            if (metrics == null) {
                metrics = new LlmCallMetrics();
                metrics.setConfigId(configId);
                metrics.setUserId(userId);
                metrics.setWindowStart(now);
                metrics.setSuccessCount(0);
                metrics.setFailureCount(0);
                metrics.setTotalLatencyMs(0L);
                metrics.setTotalInputTokens(0);
                metrics.setTotalOutputTokens(0);
            }

            if (success) {
                metrics.setSuccessCount(metrics.getSuccessCount() + 1);
            } else {
                metrics.setFailureCount(metrics.getFailureCount() + 1);
            }
            metrics.setTotalLatencyMs(metrics.getTotalLatencyMs() + latencyMs);
            metrics.setTotalInputTokens(metrics.getTotalInputTokens() + inputTokens);
            metrics.setTotalOutputTokens(metrics.getTotalOutputTokens() + outputTokens);
            metrics.setLastReportedAt(LocalDateTime.now());
            // Capture source IP for admin monitoring
            metrics.setLastIp(resolveClientIp());

            if (metrics.getId() == null) {
                llmCallMetricsDao.insert(metrics);
            } else {
                llmCallMetricsDao.updateById(metrics);
            }

            if (success) {
                circuitBreaker.reportSuccess(configId);
            } else {
                circuitBreaker.reportFailure(configId);
            }
        } catch (Exception e) {
            log.warn("[DefaultLlmCallReporter] Failed to persist metrics for configId={}: {}", configId, e.getMessage());
        }
    }

    private String resolveClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }
}