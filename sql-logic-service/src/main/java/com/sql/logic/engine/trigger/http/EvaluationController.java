package com.sql.logic.engine.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.evaluation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * REST controller for BIRD/Spider evaluation.
 */
@RestController
@RequestMapping("/api/v1/eval")
public class EvaluationController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    private final Evaluator evaluator;
    private final DatasetLoader datasetLoader;
    private final ObjectMapper objectMapper;

    public EvaluationController(Evaluator evaluator, ObjectMapper objectMapper) {
        this.evaluator = evaluator;
        this.datasetLoader = new DatasetLoader();
        this.objectMapper = objectMapper;
    }

    /**
     * Upload a dataset JSON file and start evaluation.
     */
    @PostMapping(value = "/run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> runEvaluation(
            @RequestParam("dataset") MultipartFile dataset,
            @RequestParam(value = "parallelNum", defaultValue = "4") int parallelNum) {
        try {
            List<DatasetRecord> records = datasetLoader.load(dataset.getInputStream());
            if (records.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No valid records in dataset"));
            }

            String taskId = evaluator.startEvaluation(records, dataset.getOriginalFilename(),
                    record -> {
                        // MVP: return the ground truth as "prediction" for self-test
                        // Production: calls AgenticRunner to generate actual SQL
                        return record.groundTruthSql();
                    }, parallelNum);

            return ResponseEntity.ok(Map.of(
                    "taskId", taskId,
                    "totalRecords", records.size(),
                    "status", "running"
            ));
        } catch (Exception e) {
            log.error("Failed to start evaluation", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Query evaluation progress.
     */
    @GetMapping("/{taskId}/status")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String taskId) {
        Evaluator.EvaluationProgress progress = evaluator.getProgress(taskId);
        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "completed", progress.completed(),
                "total", progress.total(),
                "elapsedMs", progress.elapsedMs(),
                "percentage", progress.total() > 0
                        ? (int) (progress.completed() * 100.0 / progress.total()) : 0
        ));
    }

    /**
     * Get evaluation report.
     */
    @GetMapping("/{taskId}/report")
    public ResponseEntity<?> getReport(@PathVariable String taskId) {
        EvaluationReport report = evaluator.getReport(taskId);
        if (report == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", report.getTaskId());
        result.put("dataset", report.getDatasetName());
        result.put("totalRecords", report.getTotalRecords());
        result.put("elapsedMs", report.getElapsedMs());

        Map<String, Object> metricsMap = new LinkedHashMap<>();
        for (var entry : report.getMetrics().entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", String.format("%.2f", entry.getValue().score()));
            m.put("passing", entry.getValue().passing());
            m.put("total", entry.getValue().total());
            m.put("percentage", String.format("%.1f%%", entry.getValue().getPercentage()));
            metricsMap.put(entry.getKey(), m);
        }
        result.put("metrics", metricsMap);

        List<Map<String, Object>> detailList = new ArrayList<>();
        for (var d : report.getDetails().subList(0, Math.min(report.getDetails().size(), 50))) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("questionId", d.questionId());
            dm.put("exactMatch", d.exactMatch());
            dm.put("prediction", d.prediction());
            dm.put("groundTruth", d.groundTruth());
            detailList.add(dm);
        }
        result.put("details", detailList);
        result.put("detailsTruncated", report.getDetails().size() > 50);

        return ResponseEntity.ok(result);
    }

    /**
     * List historical evaluation reports.
     */
    @GetMapping("/reports")
    public ResponseEntity<List<Map<String, Object>>> listReports() {
        return ResponseEntity.ok(List.of());
    }
}
