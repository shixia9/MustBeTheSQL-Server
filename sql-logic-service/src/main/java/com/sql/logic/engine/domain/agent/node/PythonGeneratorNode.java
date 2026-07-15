package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.PlanStepUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.infrastructure.util.MarkdownParserUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python Generator Node (Phase 4) — produces a runnable, sandbox-safe Python script
 * for the current plan step.
 * <p>
 * Inputs: the current step's {@code instruction} (decoded from PLAN + CURRENT_STEP),
 * the recalled {@code TABLE_RELATION} schema, and the rows of the most recent
 * {@code SQL_EXECUTION_RESULT} (fed to the script via stdin at execution time).
 * Renders {@code python-generator.st} and strips markdown fences from the LLM output.
 * Writes the raw code to {@code PYTHON_CODE}.
 * <p>
 * <b>Constraint</b>: the graph stores only the most recent SQL result in
 * {@code SQL_EXECUTION_RESULT} (ReplaceStrategy), so a Python step must directly
 * follow the SQL step whose data it consumes — the planner.st samples already
 * encode this layout.
 */
@Component
public class PythonGeneratorNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(PythonGeneratorNode.class);
    private static final String DEFAULT_PYTHON_MEMORY = "500";
    private static final String DEFAULT_PYTHON_TIMEOUT = "60";

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper;
    private final LlmCallReporter llmCallReporter;

    public PythonGeneratorNode(LlmClientManager llmClientManager,
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
        String sqlResultJson = state.value(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, "");
        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);

        String instruction = PlanStepUtil.currentInstruction(state, objectMapper, currentStep, rewriteQuery);
        String schemaInfo = (tableRelation == null || tableRelation.isBlank())
                ? "（无可用 Schema）"
                : tableRelation;
        String sampleInput = extractSampleRows(sqlResultJson);

        String planDescription = objectMapper.writeValueAsString(Map.of(
                "step", currentStep,
                "instruction", instruction
        ));

        String prompt = promptManager.render(SqlAgentSpec.PromptName.PYTHON_GENERATOR, Map.of(
                "python_memory", DEFAULT_PYTHON_MEMORY,
                "python_timeout", DEFAULT_PYTHON_TIMEOUT,
                "database_schema", schemaInfo,
                "sample_input", sampleInput,
                "plan_description", planDescription
        ));

        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.PYTHON_GENERATION, llmCallReporter);
        String raw = strategy.generateSql(prompt, null);
        String pythonCode = MarkdownParserUtil.extractRawText(raw);

        log.info("[PythonGeneratorNode] step={} generated Python ({} chars)", currentStep, pythonCode.length());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(SqlAgentSpec.StateKey.PYTHON_CODE, pythonCode);
        return out;
    }

    /**
     * Extract the rows array from the SQL execution result JSON to seed the
     * script's stdin input. Falls back to an empty array when no rows are present.
     */
    @SuppressWarnings("unchecked")
    private String extractSampleRows(String sqlResultJson) {
        if (sqlResultJson == null || sqlResultJson.isBlank()) {
            return "[]";
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(sqlResultJson, Map.class);
            Object rows = parsed.get("rows");
            if (rows == null) {
                return "[]";
            }
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            log.warn("[PythonGeneratorNode] Could not parse SQL_EXECUTION_RESULT for sample input: {}", e.getMessage());
            return "[]";
        }
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