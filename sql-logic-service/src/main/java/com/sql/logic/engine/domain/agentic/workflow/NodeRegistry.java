package com.sql.logic.engine.domain.agentic.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of available workflow node types for the frontend node panel.
 * Provides metadata (icon, description, category) and default factory values for each node type.
 */
public class NodeRegistry {

    private static final List<Map<String, Object>> NODE_TYPES = List.of(
            nodeType("start", "Start", "Entry point — receives user input", "flow",
                    Map.of("title", "Start")),
            nodeType("end", "End", "Exit point — returns final result", "flow",
                    Map.of("title", "End")),
            nodeType("agent", "ManagerAgent", "Orchestrates the multi-agent workflow", "agent",
                    Map.of("agentName", "ManagerAgent", "title", "Manager")),
            nodeType("agent", "PlannerAgent", "Decomposes tasks into execution plan steps", "agent",
                    Map.of("agentName", "PlannerAgent", "title", "Planner")),
            nodeType("agent", "DataScientistAgent", "Generates and executes SQL queries", "agent",
                    Map.of("agentName", "DataScientistAgent", "title", "Data Scientist")),
            nodeType("agent", "CodeAssistantAgent", "Generates and executes Python code", "agent",
                    Map.of("agentName", "CodeAssistantAgent", "title", "Code Assistant")),
            nodeType("agent", "DashboardAssistantAgent", "Generates analysis reports", "agent",
                    Map.of("agentName", "DashboardAssistantAgent", "title", "Dashboard")),
            nodeType("agent", "ToolAssistantAgent", "Calls MCP tools", "agent",
                    Map.of("agentName", "ToolAssistantAgent", "title", "Tool Assistant")),
            nodeType("condition", "Condition", "Conditional branch routing", "flow",
                    Map.of("title", "Condition")),
            nodeType("resource", "DatabaseResource", "Database schema context injection", "resource",
                    Map.of("title", "Database"))
    );

    private static Map<String, Object> nodeType(String type, String label, String description,
                                                  String category, Map<String, Object> defaults) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        entry.put("label", label);
        entry.put("description", description);
        entry.put("category", category);
        entry.put("defaults", defaults);
        return entry;
    }

    /**
     * Get all available node types for the frontend node panel.
     */
    public List<Map<String, Object>> getNodeTypes() {
        return NODE_TYPES;
    }
}
