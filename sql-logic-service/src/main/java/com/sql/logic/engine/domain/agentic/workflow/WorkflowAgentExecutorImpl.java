package com.sql.logic.engine.domain.agentic.workflow;

import com.sql.logic.engine.application.service.DatabaseMetaDataService;
import com.sql.logic.engine.domain.agent.core.AgentSseCodec;
import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Bridges the {@link WorkflowEngine.WorkflowAgentExecutor} interface to the
 * Spring-managed Agent beans.
 * <p>
 * Maps agent names from workflow nodes (e.g. "ManagerAgent", "PlannerAgent")
 * to their corresponding Spring beans and calls {@link Agent#generateReply}.
 */
public class WorkflowAgentExecutorImpl implements WorkflowEngine.WorkflowAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(WorkflowAgentExecutorImpl.class);

    /** Map of agentName → Agent bean, populated at construction time. */
    private final Map<String, Agent> agentMap;
    private final AgentSseCodec codec;
    private final DatabaseMetaDataService databaseMetaDataService;

    public WorkflowAgentExecutorImpl(Map<String, Agent> agentMap, AgentSseCodec codec,
                                      DatabaseMetaDataService databaseMetaDataService) {
        this.agentMap = Map.copyOf(agentMap);
        this.codec = codec;
        this.databaseMetaDataService = databaseMetaDataService;
        log.info("[WorkflowAgentExecutor] Initialized with {} agents: {}",
                agentMap.size(), agentMap.keySet());
    }

    @Override
    public CompletableFuture<String> execute(WorkflowNode node, Map<String, Object> inputValues) {
        String nodeType = node.getType();
        String agentName = node.getData() != null ? node.getData().getAgentName() : null;

        log.info("[WorkflowAgentExecutor] Executing node id={}, type={}, agentName={}",
                node.getId(), nodeType, agentName);

        return switch (nodeType) {
            case "start" -> executeStartNode(node, inputValues);
            case "end" -> executeEndNode(node, inputValues);
            case "agent" -> executeAgentNode(node, inputValues);
            case "condition" -> executeConditionNode(node, inputValues);
            case "resource" -> executeResourceNode(node, inputValues);
            default -> CompletableFuture.completedFuture(
                    "{\"nodeId\":\"" + node.getId() + "\",\"type\":\"" + nodeType + "\"}");
        };
    }

    private CompletableFuture<String> executeStartNode(WorkflowNode node, Map<String, Object> inputValues) {
        String userInput = Objects.toString(inputValues.get("userInput"), "");
        return CompletableFuture.completedFuture(userInput);
    }

    private CompletableFuture<String> executeEndNode(WorkflowNode node, Map<String, Object> inputValues) {
        // Aggregate all upstream outputs into a single result
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"WORKFLOW_RESULT\",\"results\":{");
        boolean first = true;
        for (var entry : inputValues.entrySet()) {
            if ("userInput".equals(entry.getKey())) continue;
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
            first = false;
        }
        sb.append("}}");
        return CompletableFuture.completedFuture(sb.toString());
    }

    private CompletableFuture<String> executeAgentNode(WorkflowNode node, Map<String, Object> inputValues) {
        String agentName = node.getData() != null ? node.getData().getAgentName() : null;
        if (agentName == null || agentName.isBlank()) {
            return CompletableFuture.completedFuture(
                    "{\"error\":\"No agentName configured for node " + node.getId() + "\"}");
        }

        Agent agent = agentMap.get(agentName);
        if (agent == null) {
            // Try case-insensitive lookup
            agent = agentMap.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(agentName))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        if (agent == null) {
            String msg = "Agent '" + agentName + "' not found in registry. Available: " + agentMap.keySet();
            log.warn("[WorkflowAgentExecutor] {}", msg);
            return CompletableFuture.completedFuture("{\"error\":\"" + msg + "\"}");
        }

        // Build the input message from upstream outputs
        String userInput = Objects.toString(inputValues.get("userInput"), "");
        // Gather upstream node outputs as context
        StringBuilder contextBuilder = new StringBuilder();
        for (var entry : inputValues.entrySet()) {
            if ("userInput".equals(entry.getKey())) continue;
            Object val = entry.getValue();
            if (val instanceof String s && !s.isBlank()) {
                contextBuilder.append("## Output from upstream node `")
                        .append(entry.getKey()).append("`:\n")
                        .append(truncate(s, 4000)).append("\n\n");
            }
        }

        String observation = contextBuilder.length() > 0
                ? "User request: " + userInput + "\n\nUpstream context:\n" + contextBuilder.toString()
                : userInput;

        // Build message context with relevant runtime params
        Map<String, Object> msgContext = new LinkedHashMap<>();
        if (inputValues.containsKey("connectionId")) msgContext.put("connectionId", inputValues.get("connectionId"));
        if (inputValues.containsKey("schemaName")) msgContext.put("schemaName", inputValues.get("schemaName"));
        if (inputValues.containsKey("tableName")) msgContext.put("tableName", inputValues.get("tableName"));
        if (inputValues.containsKey("userId")) msgContext.put("userId", inputValues.get("userId"));
        if (inputValues.containsKey("llmConfigId")) msgContext.put("llmConfigId", inputValues.get("llmConfigId"));
        if (inputValues.containsKey("threadId")) msgContext.put("threadId", inputValues.get("threadId"));

        AgentMessage inputMsg = AgentMessage.user(observation);
        // Attach context to the message
        for (var entry : msgContext.entrySet()) {
            inputMsg = inputMsg.withContext(entry.getKey(), entry.getValue());
        }

        final AgentMessage finalMsg = inputMsg;
        return agent.generateReply(finalMsg, null, List.of(), List.of())
                .thenApply(reply -> {
                    String content = reply.content() != null ? reply.content() : "";
                    boolean success = reply.success();
                    String actionContent = reply.actionReport() != null
                            ? reply.actionReport().content() : "";

                    // Serialize the agent's reply as JSON
                    return "{"
                            + "\"nodeId\":\"" + node.getId() + "\","
                            + "\"agentName\":\"" + agentName + "\","
                            + "\"success\":" + success + ","
                            + "\"content\":" + escapeJson(content) + ","
                            + "\"actionOutput\":" + escapeJson(actionContent)
                            + "}";
                })
                .exceptionally(e -> {
                    log.error("[WorkflowAgentExecutor] Agent '{}' failed: {}", agentName, e.getMessage());
                    return "{\"nodeId\":\"" + node.getId() + "\",\"error\":\"" + e.getMessage() + "\"}";
                });
    }

    private CompletableFuture<String> executeConditionNode(WorkflowNode node, Map<String, Object> inputValues) {
        // Simple condition evaluation based on upstream outputs
        // The conditionField from node config determines which part of the output to check
        Map<String, Object> inputs = node.getData() != null ? node.getData().getInputsValues() : Map.of();
        String conditionField = Objects.toString(inputs.getOrDefault("conditionField", "nextNode"), "");

        // Check the most recent upstream output for the condition
        String upstreamOutput = "";
        for (var entry : inputValues.entrySet()) {
            if (!"userInput".equals(entry.getKey())) {
                upstreamOutput = Objects.toString(entry.getValue(), "");
            }
        }

        return CompletableFuture.completedFuture(
                "{\"nodeId\":\"" + node.getId() + "\",\"condition\":\"" + conditionField + "\",\"output\":\"" + upstreamOutput + "\"}");
    }

    private CompletableFuture<String> executeResourceNode(WorkflowNode node, Map<String, Object> inputValues) {
        String title = node.getData() != null && node.getData().getTitle() != null
                ? node.getData().getTitle() : "Database";
        Long connectionId = toLong(inputValues.get("connectionId"));
        String schemaName = nullableString(inputValues.get("schemaName"));
        String tableName = nullableString(inputValues.get("tableName"));

        if (connectionId == null) {
            return CompletableFuture.completedFuture(
                    resourceJson(node.getId(), title, "_No database connection configured for this node._"));
        }

        try {
            StringBuilder md = new StringBuilder();
            md.append("**Connection:** #").append(connectionId);
            if (schemaName != null) md.append(" · **Schema:** ").append(schemaName);
            if (tableName != null) md.append(" · **Table:** ").append(tableName);
            md.append("\n\n");

            List<String> tables = databaseMetaDataService.getTableNames(connectionId, schemaName);
            if (tables == null || tables.isEmpty()) {
                md.append("_No tables found_.");
            } else {
                md.append("**Available tables:** ").append(String.join(", ", tables));
            }

            // If a specific table is selected, inject its DDL so downstream agents
            // (e.g. DataScientistAgent) know the exact column structure.
            if (tableName != null) {
                try {
                    String ddl = databaseMetaDataService.getTableDDL(connectionId, schemaName, tableName);
                    if (ddl != null && !ddl.isBlank()) {
                        md.append("\n\n**DDL — ").append(tableName).append(":**\n\n```sql\n")
                          .append(ddl).append("\n```");
                    }
                } catch (Exception ignored) {
                    // DDL is best-effort; table list above is the primary context.
                }
            }

            return CompletableFuture.completedFuture(resourceJson(node.getId(), title, md.toString()));
        } catch (Exception e) {
            log.warn("[WorkflowAgentExecutor] Resource node {} failed: {}", node.getId(), e.getMessage());
            return CompletableFuture.completedFuture(resourceJson(node.getId(), title,
                    "Failed to load database metadata: " + e.getMessage()));
        }
    }

    private static String resourceJson(String nodeId, String title, String content) {
        return "{"
                + "\"nodeId\":\"" + nodeId + "\","
                + "\"type\":\"resource\","
                + "\"title\":" + escapeJson(title) + ","
                + "\"content\":" + escapeJson(content)
                + "}";
    }

    private static Long toLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static String nullableString(Object v) {
        if (v == null) return null;
        String s = Objects.toString(v, "");
        return s.isBlank() ? null : s;
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...[truncated]";
    }
}
