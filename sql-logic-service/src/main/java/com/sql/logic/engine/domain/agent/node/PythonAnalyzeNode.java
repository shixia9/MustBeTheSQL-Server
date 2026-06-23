package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python Analyze Node (Phase 4) — turns the structured Python output into a natural
 * language conclusion and accumulates it into {@code EXECUTION_OUTPUT} (a per-step map),
 * then advances {@code CURRENT_STEP} so the dispatcher routes to the next plan step.
 * <p>
 * The flow does not branch on Python failure: when {@code PYTHON_RESULT.success=false},
 * the analyze LLM renders a clean "分析过程中出现错误" summary (as the python-analyze.st
 * instructs), so the report node still produces a coherent narrative.
 */
@Component
public class PythonAnalyzeNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(PythonAnalyzeNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper;

    public PythonAnalyzeNode(LlmClientManager llmClientManager,
                             PromptManager promptManager,
                             ObjectMapper objectMapper) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }
        String pythonResultJson = state.value(SqlAgentSpec.StateKey.PYTHON_RESULT, "");
        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);

        // Render the python output (success: stdout JSON, failure: the error) for the summarize prompt.
        String pythonOutput;
        boolean success = true;
        try {
            Map<String, Object> parsed = objectMapper.readValue(pythonResultJson, Map.class);
            Object s = parsed.get("success");
            success = s instanceof Boolean && (Boolean) s;
            Object out = parsed.get("output");
            if (success) {
                pythonOutput = out == null ? "" : String.valueOf(out);
            } else {
                pythonOutput = "分析过程中出现错误。";
            }
        } catch (Exception e) {
            pythonOutput = "分析过程中出现错误。";
            success = false;
        }

        String prompt = promptManager.render(SqlAgentSpec.PromptName.PYTHON_ANALYZE, Map.of(
                "user_query", rewriteQuery == null ? "" : rewriteQuery,
                "python_output", pythonOutput
        ));

        LLMStrategy strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
        String analyze = strategy.generateSql(prompt, null);
        if (analyze == null) analyze = success ? pythonOutput : "分析过程中出现错误。";
        analyze = analyze.trim();

        // Accumulate into EXECUTION_OUTPUT (a per-step map). Read the existing map (or start fresh)
        // and replace the whole key under ReplaceStrategy — graph state keys round-trip as plain maps.
        Map<String, String> executionOutput = new LinkedHashMap<>();
        Object existing = state.value(SqlAgentSpec.StateKey.EXECUTION_OUTPUT, (Object) null);
        if (existing instanceof Map) {
            ((Map<String, String>) existing).forEach((k, v) -> executionOutput.put(k, v));
        }
        executionOutput.put("step_" + currentStep, analyze);

        log.info("[PythonAnalyzeNode] step={} analyzed (success={}, conclusionLen={}); advancing to step {}.",
                currentStep, success, analyze.length(), currentStep + 1);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(SqlAgentSpec.StateKey.EXECUTION_OUTPUT, executionOutput);
        out.put(SqlAgentSpec.StateKey.PYTHON_ANALYSIS_RESULT, analyze);
        out.put(SqlAgentSpec.StateKey.CURRENT_STEP, currentStep + 1);
        return out;
    }

    private int readInt(OverAllState state, String key, int dflt) {
        Object v = state.value(key, (Integer) null);
        if (v == null) return dflt;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}