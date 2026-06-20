package com.sql.logic.engine.domain.agent.core;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core executor for the SQL Agent StateGraph.
 * <p>
 * Compiles the graph at startup and provides a streaming execution API
 * that returns a Flux of NodeOutput events to the controller layer.
 */
@Service
public class SqlAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(SqlAgentRunner.class);

    private final CompiledGraph compiledGraph;

    public SqlAgentRunner(StateGraph sqlAgentGraph) {
        try {
            this.compiledGraph = sqlAgentGraph.compile();
            log.info("[SqlAgentRunner] Graph compiled successfully. Phase 1 chain: START → EVIDENCE_RECALL → SQL_GENERATION → REPORT → END");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compile SQL Agent StateGraph", e);
        }
    }

    /**
     * Execute the SQL Agent graph with the given inputs and stream NodeOutput events.
     *
     * @param connectionId the database connection ID
     * @param userInput     the user's natural language query
     * @param llmConfigId   the LLM configuration ID (optional, falls back to system default)
     * @param tableNames    optional list of table names for schema context
     * @return Flux of NodeOutput events for SSE streaming
     */
    public Flux<NodeOutput> execute(Long connectionId, String userInput, Long llmConfigId, List<String> tableNames) {
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put(SqlAgentSpec.StateKey.INPUT, userInput);
        initialState.put(SqlAgentSpec.StateKey.CONNECTION_ID, connectionId);
        initialState.put(SqlAgentSpec.StateKey.LLM_CONFIG_ID, llmConfigId);
        initialState.put(SqlAgentSpec.StateKey.DB_TYPE, "");
        initialState.put(SqlAgentSpec.StateKey.TABLE_NAMES, tableNames != null ? tableNames : List.of());

        log.info("[SqlAgentRunner] Starting graph execution: input='{}', connectionId={}, llmConfigId={}",
                userInput, connectionId, llmConfigId);

        return compiledGraph.stream(initialState)
                .doOnNext(output -> {
                    String nodeName = output.node();
                    log.info("[SqlAgentRunner] Node completed: {}", nodeName);
                })
                .doOnComplete(() -> log.info("[SqlAgentRunner] Graph execution completed"))
                .doOnError(e -> log.error("[SqlAgentRunner] Graph execution error", e));
    }

    /**
     * Convenience overload without table names.
     */
    public Flux<NodeOutput> execute(Long connectionId, String userInput, Long llmConfigId) {
        return execute(connectionId, userInput, llmConfigId, null);
    }

    /**
     * Get the compiled graph (for testing or advanced usage).
     */
    public CompiledGraph getCompiledGraph() {
        return compiledGraph;
    }
}