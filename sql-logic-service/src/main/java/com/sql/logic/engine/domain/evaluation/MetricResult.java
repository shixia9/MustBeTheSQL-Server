package com.sql.logic.engine.domain.evaluation;

/**
 * Result of evaluating a single record with a metric.
 */
public record MetricResult(
        String metricName,
        double score,
        boolean passing,
        String details) {

    public static MetricResult pass(String metricName, double score, String details) {
        return new MetricResult(metricName, score, true, details);
    }

    public static MetricResult fail(String metricName, double score, String details) {
        return new MetricResult(metricName, score, false, details);
    }
}
