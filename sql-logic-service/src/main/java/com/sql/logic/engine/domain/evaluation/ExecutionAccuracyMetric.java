package com.sql.logic.engine.domain.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Execution Accuracy metric: compares the result sets of prediction SQL and ground truth SQL.
 * Two result sets are considered equal if they contain the same rows (ignoring column order).
 */
public class ExecutionAccuracyMetric implements EvaluationMetric {

    private static final Logger log = LoggerFactory.getLogger(ExecutionAccuracyMetric.class);

    @Override
    public String name() {
        return "execution_accuracy";
    }

    @Override
    public MetricResult compute(String prediction, String groundTruth, String context) {
        // Execution accuracy requires actual database execution.
        // In the MVP, we rely on the context parameter to pass execution results.
        // The Evaluator handles the actual DB execution and passes results here.

        // When execution results are embedded in context:
        if (context != null && !context.isBlank()) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                var node = om.readTree(context);
                if (node.has("predictionRows") && node.has("groundTruthRows")) {
                    var predRows = node.get("predictionRows");
                    var gtRows = node.get("groundTruthRows");
                    if (areResultsEqual(predRows, gtRows)) {
                        return MetricResult.pass(name(), 1.0, "result sets match");
                    }
                    return MetricResult.fail(name(), 0.0, "result sets differ");
                }
            } catch (Exception ignored) {}
        }

        log.debug("Cannot compare execution results without DB execution context");
        return MetricResult.fail(name(), 0.0, "execution context not available — use ExactMatch instead");
    }

    private boolean areResultsEqual(com.fasterxml.jackson.databind.JsonNode predRows,
                                     com.fasterxml.jackson.databind.JsonNode gtRows) {
        if (predRows.size() != gtRows.size()) return false;

        Set<String> predSet = rowsToStringSet(predRows);
        Set<String> gtSet = rowsToStringSet(gtRows);
        return predSet.equals(gtSet);
    }

    private Set<String> rowsToStringSet(com.fasterxml.jackson.databind.JsonNode rows) {
        Set<String> set = new HashSet<>();
        for (var row : rows) {
            List<String> values = new ArrayList<>();
            var fields = row.fields();
            while (fields.hasNext()) {
                var f = fields.next();
                values.add(String.valueOf(f.getValue()));
            }
            Collections.sort(values);
            set.add(String.join("|", values));
        }
        return set;
    }
}
