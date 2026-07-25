package com.sql.logic.engine.domain.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Evaluation engine: runs a dataset through the agent pipeline and computes metrics.
 */
@Service
public class Evaluator {

    private static final Logger log = LoggerFactory.getLogger(Evaluator.class);

    private final List<EvaluationMetric> metrics;
    private final ExecutorService executor;

    public Evaluator() {
        this.metrics = List.of(new ExactMatchMetric(), new ExecutionAccuracyMetric());
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Evaluation progress for status polling.
     */
    public record EvaluationProgress(int completed, int total, long elapsedMs) {}

    private final Map<String, EvaluationProgress> progressMap = new ConcurrentHashMap<>();
    private final Map<String, EvaluationReport> reportMap = new ConcurrentHashMap<>();

    /**
     * Start an evaluation task.
     */
    public String startEvaluation(List<DatasetRecord> dataset, String datasetName,
                                   SqlEvaluator sqlEvaluator, int parallelNum) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        progressMap.put(taskId, new EvaluationProgress(0, dataset.size(), 0L));

        long startTime = System.currentTimeMillis();
        AtomicInteger completed = new AtomicInteger(0);
        List<EvaluationReport.RecordResult> details = Collections.synchronizedList(new ArrayList<>());

        Semaphore semaphore = new Semaphore(Math.max(1, parallelNum));
        List<CompletableFuture<Void>> futures = dataset.stream()
                .map(record -> CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            // Call agent to generate SQL
                            String prediction = sqlEvaluator.generateSql(record);
                            EvaluationReport.RecordResult rr = new EvaluationReport.RecordResult(
                                    record.questionId(),
                                    metrics.get(0).compute(prediction, record.groundTruthSql(), record.schemaDdl()).score(),
                                    0.0, // execution accuracy computed separately
                                    prediction, record.groundTruthSql());
                            details.add(rr);
                            int done = completed.incrementAndGet();
                            progressMap.put(taskId, new EvaluationProgress(done, dataset.size(),
                                    System.currentTimeMillis() - startTime));
                        } finally {
                            semaphore.release();
                        }
                    } catch (Exception e) {
                        log.warn("Evaluation failed for question {}: {}", record.questionId(), e.getMessage());
                        completed.incrementAndGet();
                    }
                }, executor))
                .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    Map<String, EvaluationReport.MetricSummary> metricSummaries = new LinkedHashMap<>();
                    for (EvaluationMetric metric : metrics) {
                        long passing = details.stream()
                                .filter(r -> metric.compute(r.prediction(), r.groundTruth(), "").passing())
                                .count();
                        double avgScore = details.stream()
                                .mapToDouble(r -> metric.compute(r.prediction(), r.groundTruth(), "").score())
                                .average().orElse(0.0);
                        metricSummaries.put(metric.name(),
                                new EvaluationReport.MetricSummary(avgScore, (int) passing, details.size()));
                    }

                    // By difficulty
                    Map<String, Map<String, EvaluationReport.MetricSummary>> byDifficulty = new LinkedHashMap<>();

                    EvaluationReport report = new EvaluationReport(taskId, datasetName,
                            dataset.size(), metricSummaries, byDifficulty, details, elapsed);
                    reportMap.put(taskId, report);
                    log.info("Evaluation {} completed: {} records in {}ms", taskId, dataset.size(), elapsed);
                });

        return taskId;
    }

    /**
     * Get evaluation progress.
     */
    public EvaluationProgress getProgress(String taskId) {
        return progressMap.getOrDefault(taskId, new EvaluationProgress(0, 0, 0L));
    }

    /**
     * Get evaluation report.
     */
    public EvaluationReport getReport(String taskId) {
        return reportMap.get(taskId);
    }

    /**
     * Functional interface for SQL generation during evaluation.
     */
    @FunctionalInterface
    public interface SqlEvaluator {
        String generateSql(DatasetRecord record) throws Exception;
    }
}
