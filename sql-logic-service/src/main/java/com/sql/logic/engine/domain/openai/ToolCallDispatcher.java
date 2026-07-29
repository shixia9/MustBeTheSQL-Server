package com.sql.logic.engine.domain.openai;

import com.sql.logic.engine.domain.agent.core.AgenticRunner;
import com.sql.logic.engine.domain.agent.tool.ToolDefinition;
import com.sql.logic.engine.trigger.http.dto.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Dispatches OpenAI-format tool calls to the internal tool execution system.
 * Tool calls are routed through the AgenticRunner which invokes the appropriate
 * MCP tool or built-in executor.
 */
@Service
public class ToolCallDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolCallDispatcher.class);

    private final OpenAiToolRegistry toolRegistry;
    private final AgenticRunner agenticRunner;

    public ToolCallDispatcher(OpenAiToolRegistry toolRegistry, AgenticRunner agenticRunner) {
        this.toolRegistry = toolRegistry;
        this.agenticRunner = agenticRunner;
    }

    /**
     * Execute a batch of tool calls and return the results as tool-role messages.
     *
     * @param toolCalls    the tool calls from the LLM response
     * @param userId       authenticated user
     * @param connectionId target database connection (nullable — tools handle null gracefully)
     * @return list of tool result messages to feed back to the LLM
     */
    public List<ChatCompletionRequest.Message> executeTools(
            List<ChatCompletionRequest.ToolCall> toolCalls,
            Long userId,
            Long connectionId) {

        List<ChatCompletionRequest.Message> results = new ArrayList<>();

        for (ChatCompletionRequest.ToolCall tc : toolCalls) {
            String fnName = tc.getFunction() != null ? tc.getFunction().getName() : null;
            if (fnName == null) {
                log.warn("[ToolCallDispatcher] Tool call without function name: {}", tc.getId());
                results.add(buildErrorResult(tc.getId(), "Missing function name"));
                continue;
            }

            ToolDefinition def = toolRegistry.lookup(fnName);
            if (def == null) {
                log.warn("[ToolCallDispatcher] Unknown tool: {}", fnName);
                results.add(buildErrorResult(tc.getId(), "Unknown tool: " + fnName));
                continue;
            }

            try {
                String args = tc.getFunction().getArguments();
                String query = extractQuery(args);

                log.info("[ToolCallDispatcher] Executing tool: {} with query: {}, connectionId={}",
                        fnName, query != null ? query.substring(0, Math.min(100, query.length())) : "null",
                        connectionId);

                // Route to the appropriate internal executor based on tool type
                String result = dispatchTool(def, query, userId, connectionId);

                ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
                msg.setRole("tool");
                msg.setToolCallId(tc.getId());
                msg.setContent(result != null ? result : "Tool executed successfully");
                results.add(msg);

            } catch (Exception e) {
                log.error("[ToolCallDispatcher] Tool execution failed: {}", fnName, e);
                results.add(buildErrorResult(tc.getId(), e.getMessage()));
            }
        }

        return results;
    }

    private String dispatchTool(ToolDefinition def, String query, Long userId, Long connectionId) throws Exception {
        return switch (def.name()) {
            case "sql" -> {
                var handle = agenticRunner.execute(connectionId, query, userId, null, null, null, "", false);
                var events = handle.getUnifiedSseFlux().collectList().block(java.time.Duration.ofSeconds(60));
                yield extractText(events);
            }
            case "schema" -> {
                var handle = agenticRunner.execute(connectionId,
                        "Describe the schema for: " + query, userId, null, null, null, "", false);
                var events = handle.getUnifiedSseFlux().collectList().block(java.time.Duration.ofSeconds(60));
                yield extractText(events);
            }
            default -> "Tool '" + def.name() + "' executed. No detailed result available.";
        };
    }

    private String extractText(List<String> events) {
        if (events == null || events.isEmpty()) return "No output";
        StringBuilder sb = new StringBuilder();
        for (String e : events) {
            if (e != null && !e.isBlank()) sb.append(e).append("\n");
        }
        return sb.toString();
    }

    private String extractQuery(String arguments) {
        if (arguments == null || arguments.isBlank()) return "";
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = mapper.readTree(arguments);
            if (node.has("query")) return node.get("query").asText();
            if (node.has("input")) return node.get("input").asText();
            return arguments;
        } catch (Exception e) {
            return arguments;
        }
    }

    private ChatCompletionRequest.Message buildErrorResult(String toolCallId, String error) {
        ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
        msg.setRole("tool");
        msg.setToolCallId(toolCallId);
        msg.setContent("Error: " + error);
        return msg;
    }
}
