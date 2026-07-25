package com.sql.logic.engine.domain.agentic.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes a compiled workflow DAG.
 * <p>
 * Iterates through topological levels; nodes within the same level execute in parallel
 * via Virtual Threads. Conditional edges route execution based on node output.
 */
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private final WorkflowCompiler compiler = new WorkflowCompiler();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowAgentExecutor agentExecutor;

    /**
     * Functional interface for executing a single agent node.
     */
    @FunctionalInterface
    public interface WorkflowAgentExecutor {
        /**
         * Execute an agent node and return the result.
         * @param node the workflow node to execute
         * @param inputValues accumulated results from upstream nodes
         * @return execution result (the output to pass downstream)
         */
        CompletableFuture<String> execute(WorkflowNode node, Map<String, Object> inputValues);
    }

    public WorkflowEngine(WorkflowAgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
    }

    /**
     * Result of a single workflow execution.
     */
    public record WorkflowResult(
            boolean success,
            /** Map of node_id → output text */
            Map<String, String> nodeOutputs,
            String errorMessage) {

        public static WorkflowResult success(Map<String, String> outputs) {
            return new WorkflowResult(true, outputs, null);
        }

        public static WorkflowResult fail(String error) {
            return new WorkflowResult(false, Map.of(), error);
        }
    }

    /**
     * Execute the workflow definition with the given input context.
     */
    public CompletableFuture<WorkflowResult> execute(WorkflowDefinition definition,
                                                       Map<String, Object> inputContext) {
        try {
            WorkflowCompiler.CompiledWorkflow compiled = compiler.compile(definition);
            return executeLevels(compiled, inputContext);
        } catch (Exception e) {
            log.error("Workflow execution failed", e);
            return CompletableFuture.completedFuture(WorkflowResult.fail(e.getMessage()));
        }
    }

    private CompletableFuture<WorkflowResult> executeLevels(
            WorkflowCompiler.CompiledWorkflow compiled, Map<String, Object> inputContext) {
        Map<String, String> nodeOutputs = new LinkedHashMap<>();
        AtomicReference<String> currentRoute = new AtomicReference<>(null);

        return executeLevelRecursive(compiled, inputContext, nodeOutputs, 0, currentRoute);
    }

    private CompletableFuture<WorkflowResult> executeLevelRecursive(
            WorkflowCompiler.CompiledWorkflow compiled,
            Map<String, Object> inputContext,
            Map<String, String> nodeOutputs,
            int levelIndex,
            AtomicReference<String> currentRoute) {

        if (levelIndex >= compiled.levels().size()) {
            return CompletableFuture.completedFuture(WorkflowResult.success(nodeOutputs));
        }

        List<WorkflowNode> level = compiled.levels().get(levelIndex);

        // Filter nodes based on conditional routing
        List<WorkflowNode> nodesToExecute = level;
        if (currentRoute.get() != null) {
            String route = currentRoute.get();
            currentRoute.set(null);
            nodesToExecute = level.stream()
                    .filter(n -> {
                        // Find the edge that routes to this node with the current condition
                        for (var entry : compiled.edgeConditions().entrySet()) {
                            String[] parts = entry.getKey().split("→");
                            if (parts.length == 2 && parts[1].equals(n.getId()) && entry.getValue().equals(route)) {
                                return true;
                            }
                        }
                        // If no conditional edge points to this node, include it anyway
                        return level.size() == 1 || !compiled.edgeConditions().keySet().stream()
                                .anyMatch(k -> k.endsWith("→" + n.getId()));
                    }).toList();
            }

        if (nodesToExecute.isEmpty()) {
            return executeLevelRecursive(compiled, inputContext, nodeOutputs, levelIndex + 1, currentRoute);
        }

        // Execute nodes in parallel within this level
        List<CompletableFuture<Void>> futures = nodesToExecute.stream()
                .map(node -> executeNode(node, compiled, nodeOutputs, inputContext)
                        .thenAccept(output -> {
                            if (output != null) {
                                nodeOutputs.put(node.getId(), output);
                                // Check if this is a condition node → set route for next level
                                if ("condition".equals(node.getType()) && node.getData() != null) {
                                    String conditionField = (String) node.getData().getInputsValues().get("conditionField");
                                    if (conditionField != null && output.contains(conditionField)) {
                                        // Simple: extract condition value from output
                                        try {
                                            var tree = objectMapper.readTree(output);
                                            if (tree.has(conditionField)) {
                                                currentRoute.set(tree.get(conditionField).asText());
                                            }
                                        } catch (Exception ignored) {}
                                    }
                                }
                            }
                        }))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenCompose(v -> executeLevelRecursive(compiled, inputContext, nodeOutputs, levelIndex + 1, currentRoute))
                .exceptionally(e -> WorkflowResult.fail("Level " + levelIndex + " failed: " + e.getMessage()));
    }

    private CompletableFuture<String> executeNode(
            WorkflowNode node,
            WorkflowCompiler.CompiledWorkflow compiled,
            Map<String, String> nodeOutputs,
            Map<String, Object> inputContext) {

        // Gather upstream outputs
        Map<String, Object> nodeInput = new LinkedHashMap<>(inputContext);
        List<String> upstreamIds = compiled.reverseAdjacency().getOrDefault(node.getId(), List.of());
        for (String upstreamId : upstreamIds) {
            String upstreamOutput = nodeOutputs.get(upstreamId);
            if (upstreamOutput != null) {
                nodeInput.put(upstreamId, upstreamOutput);
            }
        }

        if (agentExecutor != null) {
            return agentExecutor.execute(node, nodeInput);
        }

        // Default: pass-through for non-agent nodes
        return CompletableFuture.completedFuture("{\"nodeId\":\"" + node.getId() + "\",\"type\":\"" + node.getType() + "\"}");
    }
}
