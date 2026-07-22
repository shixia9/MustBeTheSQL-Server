package com.sql.logic.engine.domain.agentic.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;

import java.util.Map;

/**
 * A single SQL candidate produced by {@link MultiCandidateSqlAction},
 * with scoring metadata for the voting/selection process.
 */
public class SqlCandidate {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String sql;
    private final Map<String, Object> metadata;
    private double ruleScore;
    private double llmScore;
    private boolean valid;

    public SqlCandidate(String sql, Map<String, Object> metadata) {
        this.sql = sql;
        this.metadata = metadata;
        this.valid = true;
        this.ruleScore = 1.0;
        this.llmScore = 0.5;
    }

    public String getSql() { return sql; }
    public Map<String, Object> getMetadata() { return metadata; }
    public double getRuleScore() { return ruleScore; }
    public double getLlmScore() { return llmScore; }
    public boolean isValid() { return valid; }

    public void setRuleScore(double ruleScore) { this.ruleScore = ruleScore; }
    public void setLlmScore(double llmScore) { this.llmScore = llmScore; }
    public void setValid(boolean valid) { this.valid = valid; }

    /** Composite score: 40% rules + 60% LLM ranking. */
    public double compositeScore() {
        return 0.4 * ruleScore + 0.6 * llmScore;
    }

    /** Convert to a user-facing message for LLM ranking context. */
    public String toDisplayString(int index) {
        return "Candidate " + index + ": " + sql;
    }
}
