package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agent.tool.ToolDefinition;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import com.sql.logic.engine.domain.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feasibility Assessment Node (Phase 3) — the gate after Schema Linking.
 * <p>
 * Asks the LLM whether the (rewritten) query can be answered with the recalled
 * schema + evidence, emitting a plain-text verdict of the form:
 * <pre>
 * 【需求类型】：《数据分析》 | 《需要澄清》 | 《自由闲聊》
 * 【语种类型】：...
 * 【需求内容】：...
 * </pre>
 * The {@code FeasibilityAssessmentEdge} routes on this substring.
 * <p>
 * For non-analysis verdicts (澄清 / 闲聊) the downstream edge jumps straight to
 * REPORT, so this node also pre-fills {@code REPORT_RESULT} with the verdict text.
 * {@code ReportNode} detects a pre-filled result and short-circuits its own LLM call.
 */
@Component
public class FeasibilityAssessmentNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(FeasibilityAssessmentNode.class);

    /** Marker tag indicating the request is answerable as a data-analysis task. */
    public static final String TAG_ANALYSIS = "《数据分析》";

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final LlmCallReporter llmCallReporter;
    private final ToolRegistry toolRegistry;

    public FeasibilityAssessmentNode(LlmClientManager llmClientManager, PromptManager promptManager,
                                     LlmCallReporter llmCallReporter,
                                     ToolRegistry toolRegistry) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.llmCallReporter = llmCallReporter;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }
        String tableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        String evidence = state.value(SqlAgentSpec.StateKey.EVIDENCE, "");

        Object llmConfigIdObj = state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null);
        Long llmConfigId = com.sql.logic.engine.domain.agent.AgentStateUtil.toLong(llmConfigIdObj);
        Object userIdObj = state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null);
        Long userId = com.sql.logic.engine.domain.agent.AgentStateUtil.toLong(userIdObj);

        // Inject conversation history so follow-up questions are understood in context
        String conversationHistory = state.value(SqlAgentSpec.StateKey.CONVERSATION_HISTORY, "");
        String multiTurn = (conversationHistory == null || conversationHistory.isBlank()) ? "" : conversationHistory;

        // Schema fallback: if Schema Linking produced nothing usable, show empty placeholder
        String recalledSchema = (tableRelation == null || tableRelation.isBlank()) ? "（无召回的 Schema）" : tableRelation;
        String evidenceText = (evidence == null || evidence.isBlank()) ? "无" : evidence;

        // Include available MCP tools as additional data sources
        String mcpToolsInfo = buildMcpInfo();

        String prompt = promptManager.render(SqlAgentSpec.PromptName.FEASIBILITY_ASSESSMENT, Map.of(
                "canonical_query", rewriteQuery,
                "recalled_schema", recalledSchema,
                "evidence", evidenceText,
                "multi_turn", multiTurn,
                "mcp_tools", mcpToolsInfo
        ));

        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.FEASIBILITY_ASSESSMENT, llmCallReporter);
        String verdict = strategy.generateSql(prompt, null);
        if (verdict == null) {
            verdict = "";
        }
        verdict = verdict.trim();

        log.info("[FeasibilityAssessmentNode] verdict: {}", verdict);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(SqlAgentSpec.StateKey.FEASIBILITY_RESULT, verdict);

        // For non-analysis verdicts, pre-fill the report so the REPORT node (jumped-to
        // via the edge) just emits this text without an extra LLM call.
        if (!verdict.contains(TAG_ANALYSIS)) {
            out.put(SqlAgentSpec.StateKey.REPORT_RESULT, extractNeedContent(verdict, rewriteQuery));
        }

        return out;
    }

    /**
     * Pull the 【需求内容】 line out of the verdict if present; otherwise fall back
     * to the full verdict text so the user at least sees what the gate decided.
     * Strips the leading colon（：or :）that follows the marker in the LLM output.
     */
    private String extractNeedContent(String verdict, String fallbackQuery) {
        if (verdict == null || verdict.isBlank()) {
            return fallbackQuery;
        }
        String marker = "【需求内容】";
        int idx = verdict.indexOf(marker);
        if (idx < 0) {
            return verdict;
        }
        String rest = verdict.substring(idx + marker.length()).trim();
        // Strip the colon separator (chinese ：or ascii :) that follows the marker
        if (rest.startsWith("：")) {
            rest = rest.substring(1).trim();
        } else if (rest.startsWith(":")) {
            rest = rest.substring(1).trim();
        }
        // Keep only the first line — the verdict may carry trailing sections.
        int nl = rest.indexOf('\n');
        return nl > 0 ? rest.substring(0, nl).trim() : rest;
    }

    private String buildMcpInfo() {
        List<ToolDefinition> mcpTools = toolRegistry.listTools().stream()
                .filter(t -> t.type().name().startsWith("MCP_"))
                .toList();
        if (mcpTools.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【可用的外部数据源 (MCP Tools)】\n");
        sb.append("除了数据库，你还可以通过以下外部工具获取实时数据。如果用户的请求无法通过数据库回答，但能被这些工具覆盖，应判定为《数据分析》。\n");
        for (ToolDefinition t : mcpTools) {
            sb.append("- ").append(t.displayName()).append(" (`").append(t.name()).append("`): ").append(t.description()).append("\n");
        }
        return sb.toString();
    }
}