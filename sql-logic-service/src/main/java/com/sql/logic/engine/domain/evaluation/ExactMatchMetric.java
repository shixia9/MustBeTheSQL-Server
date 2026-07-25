package com.sql.logic.engine.domain.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Exact Match metric: compares prediction SQL against ground truth SQL
 * after normalization (lowercase, whitespace collapse, trailing semicolons).
 */
public class ExactMatchMetric implements EvaluationMetric {

    private static final Logger log = LoggerFactory.getLogger(ExactMatchMetric.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final double EXACT_SCORE = 1.0;
    private static final double MISMATCH_SCORE = 0.0;

    @Override
    public String name() {
        return "exact_match";
    }

    @Override
    public MetricResult compute(String prediction, String groundTruth, String context) {
        if (prediction == null || groundTruth == null) {
            return MetricResult.fail(name(), MISMATCH_SCORE, "prediction or ground truth is null");
        }

        String normalizedPred = normalize(prediction);
        String normalizedGT = normalize(groundTruth);

        if (normalizedPred.equals(normalizedGT)) {
            return MetricResult.pass(name(), EXACT_SCORE, "exact match after normalization");
        }

        // Check if they differ only by aliases or formatting
        double similarity = jaccardWordSimilarity(normalizedPred, normalizedGT);
        return MetricResult.fail(name(), similarity,
                "mismatch: pred=" + truncate(normalizedPred, 80) + " | gt=" + truncate(normalizedGT, 80));
    }

    private String normalize(String sql) {
        String result = sql.trim()
                .replaceAll("`", "")
                .replaceAll("\"", "")
                .replaceAll(";", "");
        result = WHITESPACE.matcher(result).replaceAll(" ");
        return result.toLowerCase(Locale.ROOT);
    }

    private double jaccardWordSimilarity(String a, String b) {
        String[] wordsA = a.split("\\s+");
        String[] wordsB = b.split("\\s+");
        int intersection = 0;
        for (String wa : wordsA) {
            for (String wb : wordsB) {
                if (wa.equals(wb)) {
                    intersection++;
                    break;
                }
            }
        }
        int union = wordsA.length + wordsB.length - intersection;
        return union > 0 ? (double) intersection / union : 0.0;
    }

    private String truncate(String s, int len) {
        return s.length() <= len ? s : s.substring(0, len) + "...";
    }
}
