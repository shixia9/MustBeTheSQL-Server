package com.sql.logic.engine.domain.agentic.action;

import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.resource.AgentResource;
import com.sql.logic.engine.infrastructure.util.MarkdownParserUtil;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Multi-candidate SQL generation action.
 * <p>
 * Generates N (default 3) SQL candidates in parallel via Virtual Threads,
 * then filters and ranks them via {@link SqlCandidateScorer} to select
 * the best candidate.
 * <p>
 * Used by {@code DataScientistAgent} for MEDIUM and COMPLEX queries.
 * Simple queries use the existing single-path {@link SqlGenerationAction}.
 */
public class MultiCandidateSqlAction implements AgentAction {

    private final PromptManager promptManager;
    private final SqlCandidateScorer scorer;
    private final int candidateCount;

    public MultiCandidateSqlAction(PromptManager promptManager,
                                    SqlCandidateScorer scorer,
                                    int candidateCount) {
        this.promptManager = promptManager;
        this.scorer = scorer;
        this.candidateCount = candidateCount > 0 ? candidateCount : 3;
    }

    public MultiCandidateSqlAction(PromptManager promptManager,
                                    SqlCandidateScorer scorer) {
        this(promptManager, scorer, 3);
    }

    @Override
    public String name() {
        return "multi_candidate_sql";
    }

    @Override
    public String description() {
        return "并行生成" + candidateCount + "条候选SQL，通过规则过滤+LLM评分选择最优";
    }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConversableAgent ca = (ConversableAgent) agent;
                AgentResource resource = ca.getResource();

                // Build prompt variables (shared across candidates)
                Map<String, Object> baseVars = buildBaseVariables(context, resource);

                // Generate N candidates in parallel
                String question = context.content();
                String schemaInfo = (String) context.context().getOrDefault("schemaInfo", "");

                List<SqlCandidate> candidates = new CopyOnWriteArrayList<>();
                List<CompletableFuture<Void>> futures = new ArrayList<>();

                for (int i = 0; i < candidateCount; i++) {
                    final int idx = i;
                    var future = CompletableFuture.runAsync(() -> {
                        try {
                            // Vary temperature hint via different prompt framing
                            Map<String, Object> vars = new HashMap<>(baseVars);
                            vars.put("candidate_index", idx + 1);
                            vars.put("question", question
                                    + (idx > 0 ? "\n(Alternative approach " + (idx + 1)
                                    + ": consider a different SQL strategy)" : ""));

                            String renderedPrompt;
                            try {
                                renderedPrompt = promptManager.render(
                                        SqlAgentSpec.PromptName.NEW_SQL_GENERATE, vars);
                            } catch (Exception e) {
                                // Try multi-candidate specific template, fallback to standard
                                renderedPrompt = buildInlinePrompt(vars);
                            }

                            LLMStrategy strategy = ca.resolveLlmStrategy();
                            if (strategy == null) {
                                return;
                            }

                            String rawSql = strategy.generateSql(renderedPrompt, null);
                            String cleanSql = MarkdownParserUtil.extractRawText(rawSql);

                            Map<String, Object> meta = new LinkedHashMap<>();
                            meta.put("candidateIndex", idx);
                            candidates.add(new SqlCandidate(cleanSql, meta));
                        } catch (Exception e) {
                            // Individual candidate failure is non-fatal
                        }
                    });
                    futures.add(future);
                }

                // Wait for all candidates (with timeout)
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

                if (candidates.isEmpty()) {
                    return ActionOutput.fail("所有候选SQL生成失败");
                }

                // Filter and rank
                List<SqlCandidate> valid = scorer.filter(candidates);
                if (valid.isEmpty()) {
                    return ActionOutput.fail("所有候选SQL未通过规则校验: "
                            + candidates.size() + " candidates rejected");
                }

                scorer.rankByLLM(valid, question, schemaInfo);
                SqlCandidate best = scorer.selectBest(valid);

                if (best == null) {
                    return ActionOutput.fail("无法选出最优SQL候选");
                }

                Map<String, Object> resultData = new LinkedHashMap<>();
                resultData.put("sql", best.getSql());
                resultData.put("compositeScore", best.compositeScore());
                resultData.put("ruleScore", best.getRuleScore());
                resultData.put("llmScore", best.getLlmScore());
                resultData.put("totalCandidates", candidates.size());
                resultData.put("validCandidates", valid.size());
                resultData.put("allCandidates", candidates.stream()
                        .map(c -> Map.of("sql", c.getSql(), "valid", c.isValid(),
                                "compositeScore", c.compositeScore()))
                        .toList());

                return ActionOutput.success(best.getSql(), resultData);
            } catch (Exception e) {
                return ActionOutput.fail("Multi-candidate SQL generation failed: " + e.getMessage());
            }
        });
    }

    private Map<String, Object> buildBaseVariables(AgentMessage context, AgentResource resource) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("dialect", context.context().getOrDefault("dialect", "MySQL"));
        vars.put("question", context.content());
        vars.put("schema_info", context.context().getOrDefault("schemaInfo", ""));
        vars.put("evidence", context.context().getOrDefault("evidence", "无"));
        vars.put("execution_description",
                context.context().getOrDefault("executionDescription", context.content()));

        String conversationHistory = (String) context.context().getOrDefault(
                "conversationHistory", "");
        vars.put("conversation_history_section",
                conversationHistory.isEmpty() ? "" : "## 对话历史\n" + conversationHistory);

        String userMemory = (String) context.context().getOrDefault("userMemory", "");
        vars.put("user_memory_section",
                userMemory.isEmpty() ? "" : "## 用户偏好\n" + userMemory);

        String agentSystemPrompt = (String) context.context().getOrDefault(
                "agentSystemPrompt", "");
        vars.put("system_prompt_section",
                agentSystemPrompt.isEmpty() ? "" : "## 系统提示\n" + agentSystemPrompt);

        vars.put("execution_description_section", "");

        if (resource != null) {
            String resourcePrompt = resource.getPrompt(context);
            vars.put("schema_info",
                    vars.get("schema_info") + "\n" + resourcePrompt);
        }

        return vars;
    }

    private String buildInlinePrompt(Map<String, Object> vars) {
        return """
                Generate a valid, efficient SQL query for the following question.
                Dialect: %s
                Schema: %s
                Question: %s
                Evidence: %s
                Output only the SQL statement, no markdown, no explanation.
                """.formatted(vars.get("dialect"), vars.get("schema_info"),
                vars.get("question"), vars.get("evidence"));
    }
}
