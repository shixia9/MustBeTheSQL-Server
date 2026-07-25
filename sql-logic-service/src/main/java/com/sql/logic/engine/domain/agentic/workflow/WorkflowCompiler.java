package com.sql.logic.engine.domain.agentic.workflow;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Compiles a {@link WorkflowDefinition} JSON into an executable DAG.
 * <p>
 * Performs: topology sort, cycle detection, parallel group identification,
 * and validates node/edge references.
 */
public class WorkflowCompiler {

    /**
     * Represents the compiled DAG ready for execution.
     */
    public record CompiledWorkflow(
            WorkflowDefinition definition,
            /** Topologically sorted execution levels (level 0 = entry, level N = final) */
            List<List<WorkflowNode>> levels,
            /** Map of node_id → list of downstream node_ids */
            Map<String, List<String>> adjacency,
            /** Map of node_id → list of upstream node_ids */
            Map<String, List<String>> reverseAdjacency,
            /** Map of (source, target) edge → condition value */
            Map<String, String> edgeConditions) {}

    /**
     * Compile the workflow definition into an executable structure.
     */
    public CompiledWorkflow compile(WorkflowDefinition def) {
        validate(def);
        Map<String, WorkflowNode> nodeMap = buildNodeMap(def);
        Map<String, List<String>> adjacency = buildAdjacency(def);
        Map<String, List<String>> reverseAdjacency = buildReverseAdjacency(adjacency);
        Map<String, String> edgeConditions = buildEdgeConditions(def);
        List<List<WorkflowNode>> levels = computeLevels(nodeMap, adjacency, reverseAdjacency);
        return new CompiledWorkflow(def, levels, adjacency, reverseAdjacency, edgeConditions);
    }

    private void validate(WorkflowDefinition def) {
        if (def.getNodes() == null || def.getNodes().isEmpty()) {
            throw new IllegalArgumentException("Workflow must have at least one node");
        }
        if (def.getEdges() == null) def.setEdges(List.of());
        Set<String> nodeIds = def.getNodes().stream().map(WorkflowNode::getId).collect(Collectors.toSet());
        for (WorkflowEdge edge : def.getEdges()) {
            if (!nodeIds.contains(edge.getSourceNodeId())) {
                throw new IllegalArgumentException("Edge references unknown source node: " + edge.getSourceNodeId());
            }
            if (!nodeIds.contains(edge.getTargetNodeId())) {
                throw new IllegalArgumentException("Edge references unknown target node: " + edge.getTargetNodeId());
            }
        }
        // Cycle detection
        Map<String, List<String>> adj = buildAdjacency(def);
        if (hasCycle(adj, nodeIds)) {
            throw new IllegalArgumentException("Workflow contains a cycle");
        }
    }

    private Map<String, WorkflowNode> buildNodeMap(WorkflowDefinition def) {
        return def.getNodes().stream()
                .collect(Collectors.toMap(WorkflowNode::getId, n -> n, (a, b) -> a, LinkedHashMap::new));
    }

    private Map<String, List<String>> buildAdjacency(WorkflowDefinition def) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (WorkflowNode node : def.getNodes()) {
            adj.putIfAbsent(node.getId(), new ArrayList<>());
        }
        for (WorkflowEdge edge : def.getEdges()) {
            adj.get(edge.getSourceNodeId()).add(edge.getTargetNodeId());
        }
        return adj;
    }

    private Map<String, List<String>> buildReverseAdjacency(Map<String, List<String>> adjacency) {
        Map<String, List<String>> rev = new LinkedHashMap<>();
        for (var entry : adjacency.entrySet()) {
            rev.putIfAbsent(entry.getKey(), new ArrayList<>());
            for (String target : entry.getValue()) {
                rev.computeIfAbsent(target, k -> new ArrayList<>()).add(entry.getKey());
            }
        }
        return rev;
    }

    private Map<String, String> buildEdgeConditions(WorkflowDefinition def) {
        Map<String, String> conditions = new LinkedHashMap<>();
        for (WorkflowEdge edge : def.getEdges()) {
            if (edge.getCondition() != null && !edge.getCondition().isBlank()) {
                conditions.put(edge.getSourceNodeId() + "→" + edge.getTargetNodeId(), edge.getCondition());
            }
        }
        return conditions;
    }

    /**
     * Compute topological levels using Kahn's algorithm.
     * Nodes at the same level have no dependencies on each other and can execute in parallel.
     */
    private List<List<WorkflowNode>> computeLevels(
            Map<String, WorkflowNode> nodeMap,
            Map<String, List<String>> adjacency,
            Map<String, List<String>> reverseAdjacency) {
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String nodeId : nodeMap.keySet()) {
            inDegree.put(nodeId, reverseAdjacency.getOrDefault(nodeId, List.of()).size());
        }

        Queue<String> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<List<WorkflowNode>> levels = new ArrayList<>();
        while (!queue.isEmpty()) {
            int size = queue.size();
            List<WorkflowNode> level = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                String nodeId = queue.poll();
                WorkflowNode node = nodeMap.get(nodeId);
                if (node != null) level.add(node);
                for (String next : adjacency.getOrDefault(nodeId, List.of())) {
                    int newDegree = inDegree.merge(next, -1, Integer::sum);
                    if (newDegree == 0) queue.add(next);
                }
            }
            if (!level.isEmpty()) levels.add(level);
        }
        return levels;
    }

    private boolean hasCycle(Map<String, List<String>> adjacency, Set<String> nodeIds) {
        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();
        for (String nodeId : nodeIds) {
            if (dfs(nodeId, adjacency, visited, recStack)) return true;
        }
        return false;
    }

    private boolean dfs(String nodeId, Map<String, List<String>> adjacency,
                        Set<String> visited, Set<String> recStack) {
        if (recStack.contains(nodeId)) return true;
        if (visited.contains(nodeId)) return false;
        visited.add(nodeId);
        recStack.add(nodeId);
        for (String next : adjacency.getOrDefault(nodeId, List.of())) {
            if (dfs(next, adjacency, visited, recStack)) return true;
        }
        recStack.remove(nodeId);
        return false;
    }
}
