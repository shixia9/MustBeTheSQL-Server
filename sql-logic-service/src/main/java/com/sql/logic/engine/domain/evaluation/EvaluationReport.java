package com.sql.logic.engine.domain.evaluation;

import java.util.*;

/**
 * Evaluation report aggregating metrics across all dataset records.
 */
public class EvaluationReport {

    private final String taskId;
    private final String datasetName;
    private final int totalRecords;
    private final Map<String, MetricSummary> metrics;
    private final Map<String, Map<String, MetricSummary>> byDifficulty;
    private final List<RecordResult> details;
    private final long elapsedMs;

    public EvaluationReport(String taskId, String datasetName, int totalRecords,
                            Map<String, MetricSummary> metrics,
                            Map<String, Map<String, MetricSummary>> byDifficulty,
                            List<RecordResult> details, long elapsedMs) {
        this.taskId = taskId;
        this.datasetName = datasetName;
        this.totalRecords = totalRecords;
        this.metrics = metrics;
        this.byDifficulty = byDifficulty;
        this.details = details;
        this.elapsedMs = elapsedMs;
    }

    public record MetricSummary(double score, int passing, int total) {
        public double getPercentage() { return total > 0 ? (double) passing / total * 100.0 : 0.0; }
    }

    public record RecordResult(String questionId, double exactMatch, double executionAccuracy,
                                String prediction, String groundTruth) {}

    // Getters
    public String getTaskId() { return taskId; }
    public String getDatasetName() { return datasetName; }
    public int getTotalRecords() { return totalRecords; }
    public Map<String, MetricSummary> getMetrics() { return metrics; }
    public Map<String, Map<String, MetricSummary>> getByDifficulty() { return byDifficulty; }
    public List<RecordResult> getDetails() { return details; }
    public long getElapsedMs() { return elapsedMs; }
}
