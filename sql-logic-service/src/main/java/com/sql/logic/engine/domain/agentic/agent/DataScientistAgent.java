package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.action.MultiCandidateSqlAction;
import com.sql.logic.engine.domain.agentic.action.SqlCandidate;
import com.sql.logic.engine.domain.agentic.core.*;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The Data Scientist Agent — generates, executes, and fixes SQL queries.
 * <p>
 * Phase 4 enhancements:
 * <ul>
 *   <li>Multi-candidate SQL generation for MEDIUM/COMPLEX queries (via
 *       {@link MultiCandidateSqlAction})</li>
 *   <li>Enhanced {@code correctnessCheck()} with execution-based row-count
 *       validation (DB-GPT pattern)</li>
 *   <li>Simple queries use single-path {@code SqlGenerationAction}</li>
 * </ul>
 * <p>
 * Registered actions (complex mode):
 * <ol>
 *   <li>{@code MultiCandidateSqlAction} — generate N candidates, score, select best</li>
 *   <li>{@code SqlExecutionAction} — execute SQL against the database</li>
 *   <li>{@code SqlFixAction} — fix failed SQL (invoked on retry)</li>
 * </ol>
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

    /** Whether to use multi-candidate SQL generation for this request. */
    private volatile boolean multiCandidateMode = false;

    public DataScientistAgent() {
        this.profile = DEFAULT_PROFILE;
    }

    /**
     * Enable or disable multi-candidate SQL generation.
     * Called by ManagerAgent based on complexity assessment.
     */
    public void setMultiCandidateMode(boolean enabled) {
        this.multiCandidateMode = enabled;
    }

    public boolean isMultiCandidateMode() {
        return multiCandidateMode;
    }

    /**
     * Select the appropriate action based on the current message context.
     * <p>
     * Multi-candidate mode: use {@code MultiCandidateSqlAction} as primary,
     * falling back to single-path on retry with previous failure.
     * Single-candidate mode: use the original {@code SqlGenerationAction}.
     */
    @Override
    public CompletableFuture<ActionOutput> act(AgentMessage message, Agent sender) {
        if (actions.isEmpty()) {
            return CompletableFuture.completedFuture(
                    ActionOutput.fail("No actions registered on DataScientistAgent")
            );
        }

        // Retry with fix action on previous failure
        ActionOutput previousReport = message.actionReport();
        if (previousReport != null && !previousReport.isExeSuccess() && previousReport.hasRetry()) {
            for (AgentAction action : actions) {
                if ("sql_fix".equals(action.name())) {
                    return action.execute(message, this);
                }
            }
        }

        // Multi-candidate mode: find MultiCandidateSqlAction
        if (multiCandidateMode) {
            for (AgentAction action : actions) {
                if ("multi_candidate_sql".equals(action.name())) {
                    return action.execute(message, this);
                }
            }
        }

        // Default: first registered action (typically SqlGenerationAction)
        return actions.get(0).execute(message, this);
    }

    /**
     * Domain-specific verification for SQL generation and execution.
     * <p>
     * Phase 4 enhanced: includes execution-based validation when
     * SqlExecutionService results are available in the action output.
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
     * Phase 4 enhanced checks (DB-GPT {@code correctness_check} pattern):
     * <ul>
     *   <li>SQL text is not blank</li>
     *   <li>SQL passes basic syntax validation (JSQLParser)</li>
     *   <li>SQL is read-only (SELECT/WITH/SHOW/EXPLAIN)</li>
     *   <li>If execution result data contains row count, verify rows > 0</li>
     *   <li>If execution result contains error, fail with the error</li>
     * </ul>
     */
    protected VerifyResult correctnessCheck(AgentMessage message, ActionOutput actionOutput) {
        String sql = extractSql(actionOutput);
        if (sql == null || sql.isBlank()) {
            return VerifyResult.fail("生成的 SQL 为空");
        }

        // Allow EXPLAIN as a valid read-only statement
        String upper = sql.trim().toUpperCase();
        boolean looksSelect = upper.startsWith("SELECT") || upper.startsWith("WITH")
                || upper.startsWith("SHOW ") || upper.startsWith("EXPLAIN")
                || upper.startsWith("DESCRIBE ") || upper.startsWith("DESC ")
                || upper.startsWith("(");
        if (!looksSelect) {
            return VerifyResult.fail("SQL 不是只读查询语句: "
                    + upper.substring(0, Math.min(40, upper.length())));
        }

        // JSQLParser syntax validation
        try {
            net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql.trim());
        } catch (Exception e) {
            return VerifyResult.fail("SQL 语法错误: " + e.getMessage());
        }

        // Execution-based validation (DB-GPT pattern)
        if (actionOutput.data() != null) {
            // Check for execution errors
            Object errorObj = actionOutput.data().get("error");
            if (errorObj != null && !errorObj.toString().isBlank()) {
                return VerifyResult.fail("SQL 执行错误: " + errorObj);
            }

            // Check row count when available
            Object rowCount = actionOutput.data().get("rowCount");
            if (rowCount instanceof Number n) {
                if (n.intValue() <= 0) {
                    return VerifyResult.fail(
                            "SQL 执行返回0行数据，可能存在过滤条件错误或字段值不匹配");
                }
            }

            // Check rows data directly
            Object rows = actionOutput.data().get("rows");
            if (rows instanceof List list) {
                if (list.isEmpty()) {
                    return VerifyResult.fail(
                            "SQL 执行返回空结果集，请检查过滤条件是否正确");
                }
            }
        }

        return VerifyResult.PASSED;
    }

    /**
     * Extract SQL text from the action output.
     * Handles both direct SQL content and nested sql key in data map.
     */
    private String extractSql(ActionOutput ao) {
        if (ao == null) return null;

        // Multi-candidate output: sql is in the content (best candidate)
        String content = ao.content();
        if (content != null && !content.isBlank()) {
            String upper = content.trim().toUpperCase();
            if (upper.startsWith("SELECT") || upper.startsWith("WITH")
                    || upper.startsWith("SHOW ") || upper.startsWith("EXPLAIN")
                    || upper.startsWith("DESCRIBE ") || upper.startsWith("DESC ")
                    || upper.startsWith("(")) {
                return content;
            }
        }

        // Check data map for sql key (from multi-candidate action)
        if (ao.data() != null) {
            Object sql = ao.data().get("sql");
            if (sql != null && !sql.toString().isBlank()) {
                return sql.toString();
            }
        }

        return content;
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

        // Multi-candidate mode hint
        if (multiCandidateMode) {
            sb.append("\n### Multi-Candidate Mode\n");
            sb.append("You are generating SQL in a competitive mode. Multiple candidates\n");
            sb.append("will be generated and the best one selected. Focus on correctness\n");
            sb.append("and efficiency - use table aliases, proper JOINs, and clear structure.\n");
            sb.append("\n");
        }

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
