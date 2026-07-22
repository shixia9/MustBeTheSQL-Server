package com.sql.logic.engine.domain.agentic.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * LLM-based query complexity analyzer for adaptive routing.
 * <p>
 * Uses the {@code complexity-analyzer.st} prompt template to classify user queries
 * as SIMPLE, MEDIUM, COMPLEX, or CLARIFY. The ManagerAgent uses this assessment
 * to decide whether to go through the full PlannerAgent → Workers pipeline or
 * route directly to DataScientistAgent for simple queries.
 * <p>
 * Managed by ManagerAgent per Q3 decision: LLM-driven, ManagerAgent-owned.
 */
public class ComplexityRouter {
    private static final Logger log = LoggerFactory.getLogger(ComplexityRouter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;

    public ComplexityRouter(LlmClientManager llmClientManager, PromptManager promptManager) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
    }

    /**
     * Assess the complexity of a user query.
     *
     * @param userQuery      the original user question
     * @param schemaSummary  brief summary of available schema (table names, row counts)
     * @param evidenceSummary brief summary of available business knowledge
     * @param llmConfigId    optional LLM config ID from the request context
     * @return complexity assessment with level and rationale
     */
    public ComplexityAssessment assess(String userQuery, String schemaSummary,
                                        String evidenceSummary, Long llmConfigId) {
        try {
            var strategy = resolveLlm(llmConfigId);
            if (strategy == null) {
                return new ComplexityAssessment(ComplexityLevel.MEDIUM,
                        "No LLM available, defaulting to MEDIUM", null);
            }

            String prompt = buildPrompt(userQuery, schemaSummary, evidenceSummary);
            String response = strategy.chat(prompt);
            return parseResponse(response);
        } catch (Exception e) {
            log.warn("Complexity assessment failed, defaulting to MEDIUM: {}", e.getMessage());
            return new ComplexityAssessment(ComplexityLevel.MEDIUM,
                    "Assessment error: " + e.getMessage(), null);
        }
    }

    private LLMStrategy resolveLlm(Long llmConfigId) {
        if (llmClientManager == null) return null;
        if (llmConfigId != null && llmConfigId > 0) {
            var s = llmClientManager.getClient(llmConfigId);
            if (s != null) return s;
        }
        var s = llmClientManager.getClient(0L);
        if (s != null) return s;
        return llmClientManager.getAnyClient();
    }

    private String buildPrompt(String userQuery, String schemaSummary, String evidenceSummary) {
        if (promptManager != null) {
            try {
                return promptManager.render("complexity-analyzer", Map.of(
                        "rewrite_query", userQuery != null ? userQuery : "",
                        "schema_summary", schemaSummary != null ? schemaSummary : "无",
                        "evidence_summary", evidenceSummary != null ? evidenceSummary : "无"
                ));
            } catch (Exception e) {
                log.debug("PromptManager render failed, using inline prompt: {}", e.getMessage());
            }
        }
        // Fallback inline prompt if PromptManager is unavailable
        return buildInlinePrompt(userQuery, schemaSummary, evidenceSummary);
    }

    private String buildInlinePrompt(String userQuery, String schemaSummary, String evidenceSummary) {
        return """
                You are a complexity classifier for SQL query analysis pipelines.

                Classify the user question as one of:
                1. SIMPLE — answerable with a single SQL query (direct lookup, count, aggregation, simple JOIN)
                2. MEDIUM — requires 2-3 SQL steps or one SQL + light analysis
                3. COMPLEX — requires multi-step analysis, Python computation, or report generation
                4. CLARIFY — question is ambiguous or missing critical information

                User question: %s

                Schema summary: %s

                Evidence summary: %s

                Output only a JSON object: {"complexity": "SIMPLE|MEDIUM|COMPLEX|CLARIFY", "reason": "..."}
                """.formatted(userQuery, schemaSummary, evidenceSummary);
    }

    private ComplexityAssessment parseResponse(String response) {
        try {
            String json = response;
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            var node = objectMapper.readTree(json);
            String levelStr = node.get("complexity").asText().toUpperCase();
            ComplexityLevel level = switch (levelStr) {
                case "SIMPLE" -> ComplexityLevel.SIMPLE;
                case "COMPLEX" -> ComplexityLevel.COMPLEX;
                case "CLARIFY" -> ComplexityLevel.CLARIFY;
                default -> ComplexityLevel.MEDIUM;
            };
            String reason = node.has("reason") ? node.get("reason").asText() : "";
            return new ComplexityAssessment(level, reason, response);
        } catch (Exception e) {
            log.debug("Failed to parse complexity response, defaulting to MEDIUM: {}", e.getMessage());
            return new ComplexityAssessment(ComplexityLevel.MEDIUM,
                    "Parse error, raw: " + response.substring(0, Math.min(100, response.length())),
                    response);
        }
    }
}
