package com.sql.logic.engine.domain.agentic.workflow;

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

    public WorkflowAgentExecutorImpl(Map<String, Agent> agentMap, AgentSseCodec codec) {
        this.agentMap = Map.copyOf(agentMap);
        this.codec = codec;
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
        // Resource nodes inject context — for now, return schema DDL hints
        return CompletableFuture.completedFuture(
                "{\"nodeId\":\"" + node.getId() + "\",\"type\":\"resource\",\"title\":\""
                        + (node.getData() != null ? node.getData().getTitle() : "") + "\"}");
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
