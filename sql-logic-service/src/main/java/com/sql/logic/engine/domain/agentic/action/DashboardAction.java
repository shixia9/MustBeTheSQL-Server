package com.sql.logic.engine.domain.agentic.action;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.model.SqlExecutionResult;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.service.SqlExecutionService;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.vis.ChartItem;
import com.sql.logic.engine.domain.agentic.vis.VisDashboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a summary report from the results of all completed plan steps.
 * <p>
 * Phase 6 (DB-GPT level): Extracts SQLs from the LLM output, re-executes each
 * SQL to obtain fresh data, assembles {@link ChartItem} list, and wraps output
 * as {@code ```vis-dashboard\n<JSON>\n```} for frontend dashboard grid rendering.
 * Falls back to plain Markdown report if no SQLs are extractable.
 */
public class DashboardAction implements AgentAction {

    private static final Logger log = LoggerFactory.getLogger(DashboardAction.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern SQL_PATTERN =
            Pattern.compile("(?i)(SELECT|WITH)\\s+.+?(?:;|$)", Pattern.DOTALL);

    private final PromptManager promptManager;
    private final VisDashboard visDashboard;
    private SqlExecutionService sqlExecutionService;

    public DashboardAction(PromptManager promptManager, VisDashboard visDashboard) {
        this.promptManager = promptManager;
        this.visDashboard = visDashboard;
    }

    /**
     * Set the SQL execution service for re-executing dashboard SQLs.
     * Optional — if not set, falls back to Markdown-only report.
     */
    public void setSqlExecutionService(SqlExecutionService sqlExecutionService) {
        this.sqlExecutionService = sqlExecutionService;
    }

    @Override
    public String name() { return "dashboard"; }

    @Override
    public String description() { return "汇总所有步骤结果，重新执行SQL获取图表数据，生成结构化仪表盘"; }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConversableAgent ca = (ConversableAgent) agent;

                @SuppressWarnings("unchecked")
                List<Map<String, String>> stepResults =
                        (List<Map<String, String>>) context.context().get("stepResults");

                StringBuilder historySummary = new StringBuilder();
                if (stepResults != null) {
                    for (Map<String, String> step : stepResults) {
                        historySummary.append("步骤: ").append(step.getOrDefault("content", ""))
                                .append("\n结果: ").append(step.getOrDefault("result", ""))
                                .append("\n\n");
                    }
                }

                Map<String, Object> vars = new HashMap<>();
                vars.put("question", context.context().getOrDefault("question", ""));
                vars.put("history_summary", historySummary.toString());
                vars.put("additional_notes", context.context().getOrDefault("additionalNotes", ""));

                // Populate all template variables from forwarded context for rich report quality
                vars.put("system_prompt_section",
                        context.context().getOrDefault("agentSystemPrompt", ""));
                vars.put("user_requirements_and_plan",
                        "用户问题: " + context.context().getOrDefault("question", ""));
                vars.put("conversation_history_section",
                        context.context().getOrDefault("conversationHistory", ""));
                vars.put("user_memory_section",
                        context.context().getOrDefault("userMemory", ""));
                vars.put("analysis_steps_and_data", historySummary.toString());
                vars.put("summary_and_recommendations", "请基于以上数据给出总结与建议");
                vars.put("json_example", "{}");

                boolean htmlReport = Boolean.TRUE.equals(
                        context.context().getOrDefault("htmlReport", false));
                String templateName = htmlReport ? "report-generator-html" : "report-generator-plain";
                String prompt = promptManager.render(templateName, vars);
                String llmOutput = ca.resolveLlmStrategy().generateSql(prompt, null);

                // Phase 6: Try to extract ChartItems from LLM output and re-execute SQLs
                List<ChartItem> chartItems = parseChartItems(llmOutput);

                if (!chartItems.isEmpty() && sqlExecutionService != null) {
                    // Extract identity fields from message context
                    Long ctxUserId = toLong(context.context().get("userId"));
                    Long ctxConnectionId = toLong(context.context().get("connectionId"));
                    String schemaName = (String) context.context().get("schemaName");

                    // Re-execute each SQL to get fresh data
                    List<ChartItem> withData = new ArrayList<>();
                    for (ChartItem item : chartItems) {
                        try {
                            SqlExecutionResult result =
                                    sqlExecutionService.execute(ctxUserId, ctxConnectionId, item.sql(), schemaName);
                            List<Map<String, Object>> rows = result.getRows();
                            if (result.hasError()) {
                                withData.add(item.withError(result.getErrorMsg()));
                            } else {
                                withData.add(item.withData(rows));
                            }
                            log.info("[DashboardAction] Re-executed SQL for chart '{}': {} rows",
                                    item.title(), rows.size());
                        } catch (Exception e) {
                            log.warn("[DashboardAction] SQL re-execution failed for '{}': {}",
                                    item.title(), e.getMessage());
                            withData.add(item.withError(e.getMessage()));
                        }
                    }

                    // Build vis-dashboard output
                    String visOutput = visDashboard.render(withData,
                            context.context().getOrDefault("question", "Analysis Dashboard").toString());

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("dashboard", visDashboard.toJson(
                            com.sql.logic.engine.domain.agentic.vis.DashboardData.of(withData, "Dashboard")));
                    data.put("chartCount", withData.size());
                    data.put("report", llmOutput);

                    return ActionOutput.success(visOutput + "\n\n### 报告摘要\n\n" + llmOutput, data);
                }

                // Fallback: plain Markdown report
                return ActionOutput.success(llmOutput, Map.of("report", llmOutput));

            } catch (Exception e) {
                log.error("[DashboardAction] Failed", e);
                return ActionOutput.fail("Dashboard generation failed: " + e.getMessage());
            }
        });
    }

    /**
     * Parse the LLM output to extract chart item definitions.
     * Handles JSON arrays and markdown-wrapped JSON.
     */
    private List<ChartItem> parseChartItems(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) return List.of();
        try {
            String json = llmOutput.trim();
            // Try to find JSON array
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            List<Map<String, Object>> items = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return items.stream()
                    .map(m -> ChartItem.of(
                            (String) m.getOrDefault("title", "Untitled"),
                            (String) m.getOrDefault("display_type", "response_table"),
                            (String) m.getOrDefault("sql", ""),
                            (String) m.getOrDefault("thought", "")
                    ))
                    .filter(item -> !item.sql().isBlank())
                    .toList();
        } catch (Exception e) {
            log.debug("[DashboardAction] Could not parse chart items from LLM output, " +
                    "falling back to Markdown: {}", e.getMessage());
            return List.of();
        }
    }

    private static Long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
