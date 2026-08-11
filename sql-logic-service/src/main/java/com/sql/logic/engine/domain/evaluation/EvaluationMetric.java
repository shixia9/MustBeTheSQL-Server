package com.sql.logic.engine.domain.evaluation;

/**
 * A single evaluation metric that computes a score comparing prediction vs ground truth.
 */
public interface EvaluationMetric {
    String name();
    MetricResult compute(String prediction, String groundTruth, String context);
}
