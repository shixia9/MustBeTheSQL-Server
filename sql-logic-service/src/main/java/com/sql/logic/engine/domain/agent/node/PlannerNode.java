package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.Plan;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner Node (Phase 3) — produces a multi-step execution plan as JSON.
 * <p>
 * Renders {@code planner.st} with the recalled schema + evidence + a forced JSON
 * schema (via {@link BeanOutputConverter}), then parses the LLM output into a
 * {@link Plan}. The plan is stored in state as a JSON string under {@code PLAN}
 * (downstream nodes re-parse on demand — robust against OverAllState round-trips),
 * and {@code CURRENT_STEP} is initialised to 1.
 * <p>
 * No HITL in Phase 3: {@code plan_validation_error} is always empty.
 */
@Component
public class PlannerNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(PlannerNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper;
    private final LlmCallReporter llmCallReporter;

    public PlannerNode(LlmClientManager llmClientManager,
                        PromptManager promptManager,
                        ObjectMapper objectMapper,
                        LlmCallReporter llmCallReporter) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.objectMapper = objectMapper;
        this.llmCallReporter = llmCallReporter;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }
        String tableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        String evidence = state.value(SqlAgentSpec.StateKey.EVIDENCE, "");

        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));

        String schema = (tableRelation == null || tableRelation.isBlank()) ? "（无召回的 Schema）" : tableRelation;
        String evidenceText = (evidence == null || evidence.isBlank()) ? "无" : evidence;

        // Force the LLM to emit a JSON object matching the Plan schema.
        BeanOutputConverter<Plan> converter = new BeanOutputConverter<>(Plan.class);

        // Phase 4: if the user rejected the previous plan, the HITL edge pushed back their
        // feedback via CONFIRMATION_FEEDBACK. Feed it into planner.st's "用户反馈处理" section
        // so the LLM must comply with the revision requirements. Empty on first run.
        String feedback = state.value(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, "");
        String planValidationError = (feedback == null || feedback.isBlank())
                ? "（暂无用户反馈）"
                : "用户反馈：" + feedback;

        String prompt = promptManager.render(SqlAgentSpec.PromptName.PLANNER, Map.of(
                "user_question", rewriteQuery,
                "schema", schema,
                "evidence", evidenceText,
                "semantic_model", "（暂无语义模型，阶段5 接入）",
                "plan_validation_error", planValidationError,
                "format", converter.getFormat()
        ));

        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.PLANNER, llmCallReporter);
        String raw = strategy.generateSql(prompt, null);
        String rawPlan = raw == null ? "" : raw.trim();

        // Lenient parse: tolerate markdown fences / trailing prose.
        Plan plan = Plan.fromJson(objectMapper, rawPlan);

        Map<String, Object> out = new LinkedHashMap<>();
        if (plan == null || plan.executionPlan() == null || plan.executionPlan().isEmpty()) {
            log.warn("[PlannerNode] Failed to parse plan from LLM output (len={}):\n{}", rawPlan.length(), rawPlan);
            // Degraded path: a single REPORT step so the graph still terminates.
            Plan degraded = new Plan("无法解析执行计划，直接生成报告。", List.of());
            out.put(SqlAgentSpec.StateKey.PLAN, objectMapper.writeValueAsString(degraded));
        } else {
            log.info("[PlannerNode] Plan steps: {}", plan.executionPlan().size());
            out.put(SqlAgentSpec.StateKey.PLAN, objectMapper.writeValueAsString(plan));
        }

        // (Re)initialise the cursor at the first step. Reset repair counters too.
        out.put(SqlAgentSpec.StateKey.CURRENT_STEP, 1);
        out.put(SqlAgentSpec.StateKey.FIX_ATTEMPT_COUNT, 0);
        out.put(SqlAgentSpec.StateKey.SQL_ERROR, "");
        // Clear the consumed feedback so a subsequent reject doesn't re-inject stale text.
        out.put(SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, "");

        return out;
    }
}