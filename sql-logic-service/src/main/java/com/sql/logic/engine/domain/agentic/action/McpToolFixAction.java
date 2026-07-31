package com.sql.logic.engine.domain.agentic.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.tool.ToolDefinition;
import com.sql.logic.engine.domain.agent.tool.mcp.McpServerManager;
import com.sql.logic.engine.domain.agentic.core.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fixes a failed MCP tool call by re-generating the tool arguments via the LLM.
 * <p>
 * Unlike the SQL fixer, this action must NOT use {@code strategy.generateSql} —
 * the corrected payload is a plain JSON object, not SQL. The action:
 * <ol>
 *   <li>loads the real {@link ToolDefinition} (including its {@code parametersSchema})
 *       via {@link McpServerManager#getToolDefinition} so the LLM is constrained
 *       by the actual tool schema instead of a hardcoded one;</li>
 *   <li>renders the {@code mcp-tool-fix.st} prompt template via {@link PromptManager};</li>
 *   <li>calls the bound LLM strategy's plain-text {@code chat()} method;</li>
 *   <li>returns the corrected arguments under the {@code mcpToolArgs} data key so
 *       {@link McpToolAction} can pick them up on the next retry.</li>
 * </ol>
 */
public class McpToolFixAction implements AgentAction {

    public static final String NAME = "mcp_tool_fix";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerManager mcpServerManager;
    private final PromptManager promptManager;

    public McpToolFixAction(McpServerManager mcpServerManager, PromptManager promptManager) {
        this.mcpServerManager = mcpServerManager;
        this.promptManager = promptManager;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "修复失败的 MCP 工具调用";
    }

    @Override
    public CompletableFuture<ActionOutput> execute(AgentMessage context, Agent agent) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConversableAgent ca = (ConversableAgent) agent;

                String toolName = (String) context.context().getOrDefault("mcpToolName", "");
                if (toolName == null || toolName.isBlank()) {
                    return ActionOutput.fail("No MCP tool specified for fix", false);
                }

                Long userId = readUserId(context);

                // Error message: prefer previous action report content, then context.
                String errorMsg = "";
                ActionOutput prevReport = context.actionReport();
                if (prevReport != null && prevReport.content() != null) {
                    errorMsg = prevReport.content();
                }
                if (errorMsg.isBlank()) {
                    errorMsg = (String) context.context().getOrDefault("errorMessage", "");
                }

                Map<String, Object> originalArgs = readToolArgs(context);
                String originalArgsJson = MAPPER.writeValueAsString(originalArgs);

                // Load the real tool definition so the LLM is constrained by the
                // actual parameters schema (replaces the previous hardcoded schema).
                ToolDefinition toolDef = mcpServerManager.getToolDefinition(userId, toolName);
                String toolDescription = toolDef != null && toolDef.description() != null
                        ? toolDef.description() : "";
                String parametersSchema = toolDef != null && toolDef.parametersSchema() != null
                        ? toolDef.parametersSchema() : "{}";

                String task = (String) context.context().getOrDefault("originalUserInput",
                        context.content());

                Map<String, Object> vars = new HashMap<>();
                vars.put("tool_name", toolName);
                vars.put("tool_description", toolDescription);
                vars.put("parameters_schema", parametersSchema);
                vars.put("original_args", originalArgsJson);
                vars.put("error_message", errorMsg);
                vars.put("task", task != null ? task : "");

                String renderedPrompt = promptManager.render(
                        SqlAgentSpec.PromptName.MCP_TOOL_FIX, vars
                );

                // Use the plain-text chat() method — the corrected payload is a JSON
                // object, not SQL, so generateSql must NOT be used here.
                String llmOutput = ca.resolveLlmStrategy().chat(renderedPrompt);
                String fixedArgsJson = extractJson(llmOutput);

                Map<String, Object> fixedArgs = parseArgs(fixedArgsJson);

                Map<String, Object> data = new HashMap<>();
                data.put("toolName", toolName);
                data.put("mcpToolArgs", fixedArgs);
                data.put("wasFixed", true);

                return ActionOutput.success(
                        "MCP tool args regenerated for: " + toolName, data);
            } catch (Exception e) {
                return ActionOutput.fail("MCP fix failed: " + e.getMessage(), false);
            }
        });
    }

    private Long readUserId(AgentMessage context) {
        Object v = context.context().get("userId");
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readToolArgs(AgentMessage context) {
        Object v = context.context().get("mcpToolArgs");
        if (v instanceof Map<?, ?> m) {
            return new HashMap<>((Map<String, Object>) m);
        }
        return new HashMap<>();
    }

    /**
     * Extract the first JSON object from the LLM output. The LLM is instructed
     * to output only the JSON, but we defensively strip any surrounding prose
     * or code fences.
     */
    private String extractJson(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return "{}";
        }
        String s = llmOutput.trim();
        // Strip markdown code fences if present.
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
            s = s.trim();
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArgs(String json) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, Map.class);
            return new HashMap<>(parsed);
        } catch (Exception e) {
            // If the LLM produced invalid JSON, fall back to an empty map so the
            // retry attempt at least proceeds with no args rather than crashing.
            return new HashMap<>();
        }
    }
}
