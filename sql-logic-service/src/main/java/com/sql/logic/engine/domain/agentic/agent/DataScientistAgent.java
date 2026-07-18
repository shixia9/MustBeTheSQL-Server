package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The Data Scientist Agent — generates, executes, and fixes SQL queries.
 * <p>
 * Registered actions:
 * <ol>
 *   <li>{@code SqlGenerationAction} — generate SQL via LLM</li>
 *   <li>{@code SqlExecutionAction} — execute SQL against the database</li>
 *   <li>{@code SqlFixAction} — fix failed SQL (invoked on retry)</li>
 * </ol>
 * <p>
 * The {@code verify()} stage checks that:
 * <ul>
 *   <li>Action output indicates success</li>
 *   <li>SQL syntax is valid (via {@code correctnessCheck()})</li>
 *   <li>Execution results are non-empty (when applicable)</li>
 * </ul>
 */
public class DataScientistAgent extends ConversableAgent {

    public static final ProfileConfig DEFAULT_PROFILE = ProfileConfig.builder()
            .name("DataScientist")
            .role("数据科学家")
            .goal("根据数据表结构生成正确的 SQL 查询并执行验证")
            .constraints(List.of(
                    "仅生成 SELECT 查询语句",
                    "使用表别名提高可读性",
                    "禁止生成 INSERT/UPDATE/DELETE/TRUNCATE 等写操作",
                    "禁止虚构不存在的表名或列名",
                    "多表查询时标注 JOIN 条件",
                    "复杂查询添加注释说明"
            ))
            .description("精通 SQL 的数据分析专家，擅长复杂查询优化和跨表分析")
            .systemPromptTemplate("""
                    你是 {name}，{description}。
                    角色：{role}
                    目标：{goal}

                    约束条件：
                    {constraints}

                    请严格基于提供的 Schema 信息生成 SQL，不要虚构任何表名或列名。
                    生成 SQL 后，先用 EXPLAIN 检查语法，确保可执行。
                    """)
            .build();

    public DataScientistAgent() {
        this.profile = DEFAULT_PROFILE;
    }

    /**
     * Select the appropriate action based on the current message context.
     * <p>
     * If a previous action failed with a retry-able error, invoke the fix action.
     * Otherwise, invoke the primary generation action.
     */
    @Override
    public CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender) {
        if (actions.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionOutput.fail("No actions registered on DataScientistAgent")
            );
        }

        // If the context indicates a previous failure that needs fixing,
        // use the fix action (typically the last action in the list)
        ActionOutput previousReport = message.actionReport();
        if (previousReport != null && !previousReport.isExeSuccess() && previousReport.hasRetry()) {
            // Find the fix action by name
            for (AgentAction action : actions) {
                if ("sql_fix".equals(action.name())) {
                    return action.execute(message, this);
                }
            }
        }

        // Default: use the first action (SQL generation)
        return actions.get(0).execute(message, this);
    }

    /**
     * Domain-specific verification for SQL generation and execution.
     */
    @Override
    public CompletableFuture<VerifyResult> verify(AgentMessage message, Agent sender) {
        ActionOutput ao = message.actionReport();
        if (ao == null || !ao.isExeSuccess()) {
            return CompletableFuture.completedFuture(
                    VerifyResult.fail(ao != null ? ao.content() : "No action output")
            );
        }
        return CompletableFuture.completedFuture(correctnessCheck(message, ao));
    }

    /**
     * Validate the SQL result for correctness.
     * <p>
     * Checks performed:
     * <ul>
     *   <li>SQL text is not blank</li>
     *   <li>SQL passes basic syntax validation (JSQLParser)</li>
     *   <li>SQL is read-only (SELECT/WITH/SHOW)</li>
     * </ul>
     */
    protected VerifyResult correctnessCheck(AgentMessage message, ActionOutput actionOutput) {
        String sql = actionOutput.content();
        if (sql == null || sql.isBlank()) {
            return VerifyResult.fail("生成的 SQL 为空");
        }

        String upper = sql.trim().toUpperCase();
        boolean looksSelect = upper.startsWith("SELECT") || upper.startsWith("WITH")
                || upper.startsWith("SHOW ") || upper.startsWith("(");
        if (!looksSelect) {
            return VerifyResult.fail("SQL 不是只读查询语句: " + upper.substring(0, Math.min(40, upper.length())));
        }

        // Basic JSQLParser syntax validation
        try {
            net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql.trim());
        } catch (Exception e) {
            return VerifyResult.fail("SQL 语法错误: " + e.getMessage());
        }

        return VerifyResult.PASSED;
    }

    // ========================================================================
    //  Prompt building
    // ========================================================================

    @Override
    protected String buildSystemPrompt(String observation, String memoryContext,
                                        String resourceContext, Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();

        // Agent profile
        sb.append(renderProfilePrompt());
        sb.append("\n");

        // Resource context (schema DDL, FK, column samples)
        if (resourceContext != null && !resourceContext.isBlank()) {
            sb.append("### Available Resources\n");
            sb.append(resourceContext);
            sb.append("\n");
        }

        // Memory context (relevant historical knowledge)
        if (memoryContext != null && !memoryContext.isBlank()) {
            sb.append("### Relevant Context\n");
            sb.append(memoryContext);
            sb.append("\n");
        }

        return sb.toString();
    }

    @Override
    protected String buildUserPrompt(String observation, String memoryContext,
                                      String resourceContext, Map<String, Object> context) {
        return observation;
    }
}
