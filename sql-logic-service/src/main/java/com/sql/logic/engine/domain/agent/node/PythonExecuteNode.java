package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.sandbox.SandboxExecutionService;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Python Execute Node (Phase 4) — pure control node (no LLM).
 * <p>
 * Runs the script produced by {@link PythonGeneratorNode} inside the sandbox, feeding
 * the rows of the most recent {@code SQL_EXECUTION_RESULT} as JSON on stdin. Writes the
 * result (serialized as JSON: {success, output, error}) to {@code PYTHON_RESULT}. The
 * graph does NOT branch on failure — the flow continues to {@link PythonAnalyzeNode},
 * which renders a clean "分析过程中出现错误" message when the result is unsuccessful,
 * preserving the end-to-end report.
 *
 * <p>Executes via {@link SandboxExecutionService} (DB-GPT-aligned sandbox module). Each
 * invocation uses a unique ephemeral thread id and destroys the session afterwards so
 * no container leaks between graph runs.
 */
@Component
public class PythonExecuteNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(PythonExecuteNode.class);
    private static final long TIMEOUT_SECONDS = 60;

    private final SandboxExecutionService sandboxService;
    private final ObjectMapper objectMapper;

    public PythonExecuteNode(SandboxExecutionService sandboxService, ObjectMapper objectMapper) {
        this.sandboxService = sandboxService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String pythonCode = state.value(SqlAgentSpec.StateKey.PYTHON_CODE, "");
        String sqlResultJson = state.value(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT, "");
        int currentStep = readInt(state, SqlAgentSpec.StateKey.CURRENT_STEP, 1);

        String inputJson = extractRowsJson(sqlResultJson);
        String threadId = "graph-python-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("[PythonExecuteNode] step={} running sandbox Python ({}, stdin={} chars)",
                currentStep, pythonCode == null ? 0 : pythonCode.length(), inputJson.length());

        ExecutionResult result;
        try {
            result = sandboxService.executePython(threadId, pythonCode, inputJson, TIMEOUT_SECONDS, null);
        } finally {
            // Ephemeral session — destroy after each graph run so containers don't leak.
            sandboxService.destroyThreadSession(threadId);
        }

        String resultJson = objectMapper.writeValueAsString(Map.of(
                "success", result.isSuccess(),
                "output", result.stdout() == null ? "" : result.stdout(),
                "error", result.stderr() == null ? "" : result.stderr()
        ));

        log.info("[PythonExecuteNode] step={} success={} outputLen={} errorLen={}",
                currentStep, result.isSuccess(),
                result.stdout() == null ? 0 : result.stdout().length(),
                result.stderr() == null ? 0 : result.stderr().length());

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
