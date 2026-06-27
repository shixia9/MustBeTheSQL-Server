package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.dto.HitlGateDTO;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HITL Gate Node (Phase 4) — a lightweight LLM gate that decides whether the
 * generated plan needs human review before execution.
 * <p>
 * Behaviour:
 * <ul>
 *   <li>When {@code AUTO_CONFIRM=true} (frontend "auto-confirm" switch on), the node
 *       short-circuits: writes {@code NEEDS_HUMAN_REVIEW=false} and returns WITHOUT
 *       calling the LLM — zero-latency passthrough, matching Phase 3 behaviour.</li>
 *   <li>Otherwise renders {@code hitl-gate.st} and asks the LLM whether the plan
 *       involves risky/data-mutating/highly complex operations. The structured
 *       {@link HitlGateDTO} is decoded and {@code NEEDS_HUMAN_REVIEW} is written.</li>
 * </ul>
 * <p>
 * This node does NOT decide the next target — that is the job of
 * {@link com.sql.logic.engine.domain.agent.edge.HitlGateEdge}.
 */
@Component
public class HitlGateNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(HitlGateNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;

    public HitlGateNode(LlmClientManager llmClientManager, PromptManager promptManager) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        boolean autoConfirm = readBool(state, SqlAgentSpec.StateKey.AUTO_CONFIRM, true);

        if (autoConfirm) {
            log.info("[HitlGateNode] Auto-confirm ON — skipping human review (no LLM call).");
            return Map.of(SqlAgentSpec.StateKey.NEEDS_HUMAN_REVIEW, false);
        }

        String userQuestion = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (userQuestion == null || userQuestion.isBlank()) {
            userQuestion = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }
        String plan = state.value(SqlAgentSpec.StateKey.PLAN, "");

        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));

        BeanOutputConverter<HitlGateDTO> converter = new BeanOutputConverter<>(HitlGateDTO.class);
        String prompt = promptManager.render(SqlAgentSpec.PromptName.HITL_GATE, Map.of(
                "user_question", userQuestion == null ? "" : userQuestion,
                "plan", plan == null ? "" : plan,
                "format", converter.getFormat()
        ));

        boolean needsReview = false;
        String reason = "";
        try {
            LLMStrategy strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
            String raw = strategy.generateSql(prompt, null);
            HitlGateDTO dto = parse(raw, converter);
            if (dto != null && dto.needsReview() != null) {
                needsReview = dto.needsReview();
                reason = dto.reason() == null ? "" : dto.reason();
            }
        } catch (Exception e) {
            // Gate failures must never block the flow — default to "no review needed".
            log.warn("[HitlGateNode] Gate LLM call failed ({}); defaulting to no review.", e.getMessage());
        }

        log.info("[HitlGateNode] needsHumanReview={} reason='{}'", needsReview, reason);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(SqlAgentSpec.StateKey.NEEDS_HUMAN_REVIEW, needsReview);
        // Surface the reason in state so the controller can forward it to the frontend.
        out.put("hitlGateReason", reason);
        return out;
    }

    private HitlGateDTO parse(String response, BeanOutputConverter<HitlGateDTO> converter) {
        if (response == null || response.isBlank()) {
            return null;
        }
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```(?:\\w+)?\\s*\\n?", "")
                    .replaceAll("\\n?```\\s*$", "")
                    .trim();
        }
        try {
            return converter.convert(cleaned);
        } catch (Exception e) {
            log.debug("[HitlGateNode] BeanOutputConverter parse failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean readBool(OverAllState state, String key, boolean dflt) {
        Object v = state.value(key, (Boolean) null);
        if (v == null) return dflt;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }
}