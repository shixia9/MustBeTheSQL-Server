package com.sql.logic.engine.domain.agentic.hitl;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.node.HitlGateNode;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agentic graph adapter for the existing risk-based HITL gate.
 *
 * <p>The old gate already owns the prompt and structured LLM parsing rules.  The
 * agentic graph reuses that decision logic, but adds an explicit marker so the
 * Manager knows that the plan has passed through this graph boundary.</p>
 */
@Component
public class AgenticHitlGateNode extends HitlGateNode {

    public AgenticHitlGateNode(LlmClientManager llmClientManager,
                               PromptManager promptManager,
                               LlmCallReporter llmCallReporter) {
        super(llmClientManager, promptManager, llmCallReporter);
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>(super.apply(state));
        result.put(SqlAgentSpec.StateKey.HITL_GATE_EVALUATED, true);
        return result;
    }
}
