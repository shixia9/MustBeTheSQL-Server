package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hybrid SQL candidate scorer combining rule-based filtering with LLM ranking.
 * <p>
 * Phase 1 — Rule filter: eliminates candidates with syntax errors, non-SELECT
 * statements, empty SQL, or trivially broken patterns.
 * <p>
 * Phase 2 — LLM ranking: presents surviving candidates to an LLM for semantic
 * quality scoring (correctness, efficiency, readability).
 * <p>
 * Final composite: 40% rule score + 60% LLM score.
 */
public class SqlCandidateScorer {
    private static final Logger log = LoggerFactory.getLogger(SqlCandidateScorer.class);

    private final LlmClientManager llmClientManager;

    public SqlCandidateScorer(LlmClientManager llmClientManager) {
        this.llmClientManager = llmClientManager;
    }

    /**
     * Filter candidates by rule-based checks and return survivors.
     */
    public List<SqlCandidate> filter(List<SqlCandidate> candidates) {
        List<SqlCandidate> survivors = new ArrayList<>();
        for (SqlCandidate c : candidates) {
            if (c.getSql() == null || c.getSql().isBlank()) {
                c.setValid(false);
                c.setRuleScore(0.0);
                continue;
            }

            String upper = c.getSql().trim().toUpperCase();
            if (!(upper.startsWith("SELECT") || upper.startsWith("WITH")
                    || upper.startsWith("SHOW ") || upper.startsWith("("))) {
                c.setValid(false);
                c.setRuleScore(0.0);
                continue;
            }

            // Syntax check
            try {
                net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(c.getSql().trim());
            } catch (Exception e) {
                c.setValid(false);
                c.setRuleScore(0.0);
                continue;
            }

            // Rule scoring: prefer shorter SQL, penalize overly long
            double lenScore = Math.max(0.3, 1.0 - (c.getSql().length() / 2000.0));
            c.setRuleScore(Math.min(1.0, lenScore));
            survivors.add(c);
        }
        return survivors;
    }

    /**
     * Rank candidates via LLM semantic scoring.
     * <p>
     * Presents all valid candidates to the LLM and asks it to score each
     * on correctness, efficiency, and readability (scale 1-10).
     */
    public void rankByLLM(List<SqlCandidate> candidates, String question, String schemaInfo) {
        if (candidates.isEmpty()) return;

        if (candidates.size() == 1) {
            candidates.get(0).setLlmScore(0.8);
            return;
        }

        try {
            LLMStrategy strategy = resolveLlm();
            if (strategy == null) {
                // No LLM available — fall back to rule-only ranking
                for (SqlCandidate c : candidates) {
                    c.setLlmScore(c.getRuleScore());
                }
                return;
            }

            String prompt = buildRankingPrompt(candidates, question, schemaInfo);
            String response = strategy.chat(prompt);

            // Parse LLM scores (expects "Candidate N: X/10" or similar)
            parseLLMScores(candidates, response);
        } catch (Exception e) {
            log.warn("LLM ranking failed, falling back to rule scores: {}", e.getMessage());
            for (SqlCandidate c : candidates) {
                c.setLlmScore(c.getRuleScore());
            }
        }
    }

    /**
     * Select the best candidate by composite score.
     */
    public SqlCandidate selectBest(List<SqlCandidate> candidates) {
        if (candidates.isEmpty()) return null;
        return candidates.stream()
                .filter(SqlCandidate::isValid)
                .max(Comparator.comparingDouble(SqlCandidate::compositeScore))
                .orElse(null);
    }

    private LLMStrategy resolveLlm() {
        if (llmClientManager != null) {
            LLMStrategy s = llmClientManager.getClient(0L);
            if (s != null) return s;
            return llmClientManager.getAnyClient();
        }
        return null;
    }

    private String buildRankingPrompt(List<SqlCandidate> candidates, String question,
                                       String schemaInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a SQL quality evaluator. Score each SQL candidate (1-10)\n");
        sb.append("on correctness, efficiency, and readability.\n\n");
        sb.append("Question: ").append(question).append("\n\n");
        if (schemaInfo != null && !schemaInfo.isBlank()) {
            sb.append("Schema: ").append(schemaInfo).append("\n\n");
        }
        sb.append("Candidates:\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append("Candidate ").append(i + 1).append(": ")
                    .append(candidates.get(i).getSql()).append("\n\n");
        }
        sb.append("Output format (JSON): [{\"candidate\":1,\"score\":8,\"reason\":\"...\"},...]");
        return sb.toString();
    }

    private void parseLLMScores(List<SqlCandidate> candidates, String llmResponse) {
        try {
            // Try JSON array
            String json = extractJsonArray(llmResponse);
            if (json == null) {
                // Fallback: assign equal scores
                for (SqlCandidate c : candidates) c.setLlmScore(0.5);
                return;
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var nodes = mapper.readTree(json);
            for (var node : nodes) {
                int idx = node.get("candidate").asInt() - 1;
                double score = node.get("score").asDouble() / 10.0;
                if (idx >= 0 && idx < candidates.size()) {
                    candidates.get(idx).setLlmScore(Math.max(0.0, Math.min(1.0, score)));
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse LLM scores, using equal distribution: {}", e.getMessage());
            for (SqlCandidate c : candidates) c.setLlmScore(0.5);
        }
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }
}
