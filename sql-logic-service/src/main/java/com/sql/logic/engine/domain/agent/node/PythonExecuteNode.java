package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.python.PythonExecutionResult;
import com.sql.logic.engine.domain.agent.python.SimplePythonExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python Execute Node (Phase 4) — pure control node (no LLM).
 * <p>
 * Runs the script produced by {@link PythonGeneratorNode} inside the sandbox, feeding
 * the rows of the most recent {@code SQL_EXECUTION_RESULT} as JSON on stdin. Writes the
 * {@link PythonExecutionResult} (serialized as JSON: {success, output, error}) to
 * {@code PYTHON_RESULT}. The graph does NOT branch on failure — the flow continues to
 * {@link PythonAnalyzeNode}, which renders a clean "分析过程中出现错误" message when the
 * result is unsuccessful, preserving the end-to-end report.
 */
@Component
public class PythonExecuteNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(PythonExecuteNode.class);
    private static final long TIMEOUT_SECONDS = 60;

    private final SimplePythonExecutor pythonExecutor;
    private final ObjectMapper objectMapper;

    public PythonExecuteNode(SimplePythonExecutor pythonExecutor, ObjectMapper objectMapper) {
        this.pythonExecutor = pythonExecutor;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String pythonCode = state.value(SqlAgentSpec.StateKey.PYTHON_CODE, "");
        String sqlResultJson = state.value(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, "");
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);

        String inputJson = extractRowsJson(sqlResultJson);

        log.info("[PythonExecuteNode] step={} running sandbox Python ({}, stdin={} chars)",
                currentStep, pythonCode == null ? 0 : pythonCode.length(), inputJson.length());

        PythonExecutionResult result = pythonExecutor.execute(pythonCode, inputJson, TIMEOUT_SECONDS);

        String resultJson = objectMapper.writeValueAsString(Map.of(
                "success", result.success(),
                "output", result.output() == null ? "" : result.output(),
                "error", result.error() == null ? "" : result.error()
        ));

        log.info("[PythonExecuteNode] step={} success={} outputLen={} errorLen={}",
                currentStep, result.success(),
                result.output() == null ? 0 : result.output().length(),
                result.error() == null ? 0 : result.error().length());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(SqlAgentSpec.StateKey.PYTHON_RESULT, resultJson);
        return out;
    }

    /** Extract the rows array JSON from the SQL result; default to [] when unavailable. */
    @SuppressWarnings("unchecked")
    private String extractRowsJson(String sqlResultJson) {
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
            log.warn("[PythonExecuteNode] Could not parse SQL_EXECUTION_RESULT rows: {}", e.getMessage());
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