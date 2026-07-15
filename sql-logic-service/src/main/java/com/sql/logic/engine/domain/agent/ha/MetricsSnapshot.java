package com.sql.logic.engine.domain.agent.ha;

import java.util.Collections;
import java.util.Map;

public class MetricsSnapshot {

    private final Map<Long, InstanceMetrics> metrics;

    public MetricsSnapshot(Map<Long, InstanceMetrics> metrics) {
        this.metrics = metrics != null ? Collections.unmodifiableMap(metrics) : Collections.emptyMap();
    }

    public InstanceMetrics get(Long configId) {
        return metrics.get(configId);
    }

    public static class InstanceMetrics {
        private final int successCount;
        private final int failureCount;
        private final long totalLatencyMs;

        public InstanceMetrics(int successCount, int failureCount, long totalLatencyMs) {
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.totalLatencyMs = totalLatencyMs;
        }

        public double getSuccessRate() {
            long total = successCount + failureCount;
            return total == 0 ? 1.0 : (double) successCount / total;
        }

        public long getAverageLatencyMs() {
            long total = successCount + failureCount;
            return total == 0 ? 0L : totalLatencyMs / total;
        }

        public long getTotalCount() {
            return successCount + failureCount;
        }
    }
}
