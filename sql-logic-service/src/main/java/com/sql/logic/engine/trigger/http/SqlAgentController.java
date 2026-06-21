package com.sql.logic.engine.trigger.http;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.application.service.UserAppService;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.core.SqlAgentRunner;
import com.sql.logic.engine.common.dto.SqlGenerateRequest;

import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * REST controller for the SQL Agent streaming endpoint.
 * <p>
 * Provides an SSE streaming endpoint that runs the SQL Agent StateGraph
 * and emits per-node events to the frontend Agent timeline.
 * <p>
 * Event format (each SSE data line is a JSON object):
 * <pre>
 * {"nodeName":"EVIDENCE_RECALL","outputType":"FINISHED","data":{"rewriteQuery":"...","evidence":""}}
 * {"nodeName":"SQL_GENERATION","outputType":"FINISHED","data":{"sqlGenerationResult":"SELECT ..."}}
 * {"nodeName":"REPORT","outputType":"FINISHED","data":{"reportResult":"..."}}
 * {"type":"COMPLETED"}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/agent/sql")
public class SqlAgentController {

    private static final Logger log = LoggerFactory.getLogger(SqlAgentController.class);

    private final SqlAgentRunner sqlAgentRunner;
    private final UserAppService userAppService;
    private final ObjectMapper objectMapper;

    public SqlAgentController(SqlAgentRunner sqlAgentRunner,
                              UserAppService userAppService,
                              ObjectMapper objectMapper) {
        this.sqlAgentRunner = sqlAgentRunner;
        this.userAppService = userAppService;
        this.objectMapper = objectMapper;
    }

    /**
     * Stream the SQL Agent execution as SSE events.
     * <p>
     * Each node completion emits a FINISHED event with the node's output data.
     * After all nodes complete, a COMPLETED event is emitted.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamAgent(@RequestBody SqlGenerateRequest request) {
        // Authenticate user
        String currentUserIdStr = (String) StpUtil.getLoginId();
        if (currentUserIdStr == null || !currentUserIdStr.matches("\\d+")) {
            return Flux.error(new IllegalArgumentException("Invalid user ID in session"));
        }
        Long currentUserId = Long.valueOf(currentUserIdStr);

        // Verify userId matches logged-in user
        if (request.getUserId() != null && !request.getUserId().equals(currentUserId)) {
            return Flux.error(new IllegalArgumentException("UserId does not match logged-in user"));
        }

        // Check user status and token quota before generation
        try {
            userAppService.checkBeforeGeneration(currentUserId);
        } catch (Exception e) {
            return Flux.error(e);
        }

        log.info("[SqlAgentController] Starting agent stream for userId={}, connectionId={}, input='{}'",
                currentUserId, request.getConnectionId(), request.getUserInput());

        // Execute the agent graph — pass userId so nodes can resolve user-specific LLM defaults
        Flux<NodeOutput> nodeFlux = sqlAgentRunner.execute(
                request.getConnectionId(),
                request.getUserInput(),
                currentUserId,
                request.getLlmConfigId(),
                request.getTableNames()
        );

        // Convert NodeOutput to SSE JSON strings, then append COMPLETED event
        return nodeFlux
                .map(this::nodeOutputToJson)
                .filter(json -> !json.isEmpty())  // Skip empty entries (e.g. START/END nodes)
                .concatWith(Flux.just(createCompletedEvent()))
                .doOnNext(json -> log.debug("[SqlAgentController] SSE event: {}", json.substring(0, Math.min(200, json.length()))))
                .onErrorResume(e -> {
                    log.error("[SqlAgentController] SSE stream error", e);
                    return Flux.just("{\"type\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}");
                });
    }

    /**
     * Convert a NodeOutput to an SSE JSON string.
     * For the START node, skip it (frontend doesn't need it).
     * For other nodes, extract relevant state data.
     */
    private String nodeOutputToJson(NodeOutput output) {
        try {
            String nodeName = output.node();

            // Skip START/END pseudo-nodes — matching library constants (uppercase)
            if ("__start__".equalsIgnoreCase(nodeName) || "__end__".equalsIgnoreCase(nodeName)) {
                return "";
            }

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("nodeName", nodeName);
            event.put("outputType", "FINISHED");

            // Extract relevant data from node state
            OverAllState state = output.state();
            if (state != null) {
                Map<String, Object> data = extractNodeData(nodeName, state);
                event.put("data", data);
            }

            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("[SqlAgentController] Failed to serialize NodeOutput", e);
            return "{\"nodeName\":\"ERROR\",\"outputType\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Sensitive state keys that should never be sent to the frontend.
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "connectionId", "llmConfigId", "userId"
    );

    /**
     * Extract relevant state data based on which node just completed.
     * Filters out sensitive keys like connectionId, llmConfigId, userId.
     */
    private Map<String, Object> extractNodeData(String nodeName, OverAllState state) {
        Map<String, Object> data = new LinkedHashMap<>();

        switch (nodeName) {
            case SqlAgentSpec.Node.EVIDENCE_RECALL:
                data.put("rewriteQuery", state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, ""));
                data.put("evidence", state.value(SqlAgentSpec.StateKey.EVIDENCE, ""));
                break;
            case SqlAgentSpec.Node.SCHEMA_LINKING:
                data.put("tableRelation", state.value(SqlAgentSpec.StateKey.TABLE_RELATION, ""));
                data.put("filteredTables", extractFilteredTableNames(state));
                break;
            case SqlAgentSpec.Node.SQL_GENERATION:
                data.put("sql", state.value(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT, ""));
                break;
            case SqlAgentSpec.Node.REPORT:
                data.put("report", state.value(SqlAgentSpec.StateKey.REPORT_RESULT, ""));
                break;
            // Phase 3+ nodes will add their data extraction here
            default:
                // Generic: include non-sensitive state entries only
                for (String key : state.data().keySet()) {
                    if (!SENSITIVE_KEYS.contains(key)) {
                        Object val = state.value(key, null);
                        if (val != null) {
                            data.put(key, val);
                        }
                    }
                }
                break;
        }

        return data;
    }

    /**
     * Extract filtered table names from the state.
     * Attempts to parse them from TABLE_NAMES if available, otherwise extracts
     * table names from the TABLE_RELATION schema string.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractFilteredTableNames(OverAllState state) {
        // First try TABLE_NAMES from state (user-selected or Schema Linking result)
        Object tableNamesObj = state.value(SqlAgentSpec.StateKey.TABLE_NAMES, null);
        if (tableNamesObj instanceof List<?>) {
            List<String> names = (List<String>) tableNamesObj;
            if (!names.isEmpty()) {
                return names;
            }
        }

        // Fall back to extracting table names from the TABLE_RELATION string
        String tableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        if (tableRelation != null && !tableRelation.isBlank()) {
            // Extract table names from "# Table: tableName" patterns in the schema prompt
            return Pattern.compile("# Table:\\s*(\\w+)")
                    .matcher(tableRelation)
                    .results()
                    .map(m -> m.group(1))
                    .collect(Collectors.toList());
        }

        return List.of();
    }

    /**
     * Create a terminal COMPLETED event.
     */
    private String createCompletedEvent() {
        try {
            Map<String, Object> completed = Map.of("type", "COMPLETED");
            return objectMapper.writeValueAsString(completed);
        } catch (Exception e) {
            return "{\"type\":\"COMPLETED\"}";
        }
    }
}