package com.sql.logic.engine.domain.openai;

import com.sql.logic.engine.domain.agent.tool.ToolDefinition;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import com.sql.logic.engine.trigger.http.dto.ChatCompletionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Registry that exposes available tools in OpenAI-compatible {@code tools} format.
 * Sources tools from the built-in {@code ToolRegistry} and MCP-connected tools.
 */
@Component
public class OpenAiToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(OpenAiToolRegistry.class);

    private final ToolRegistry toolRegistry;

    public OpenAiToolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Build the tools array for an OpenAI-compatible request.
     * Converts each registered tool's metadata to the OpenAI function-calling schema.
     */
    public List<ChatCompletionRequest.Tool> buildTools() {
        List<ChatCompletionRequest.Tool> tools = new ArrayList<>();
        for (var td : toolRegistry.listTools()) {
            ChatCompletionRequest.FunctionDef fn = new ChatCompletionRequest.FunctionDef();
            fn.setName(td.name());
            fn.setDescription(td.description() != null ? td.description() : td.displayName());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            Map<String, Object> props = new LinkedHashMap<>();
            // Minimal schema: the LLM can infer parameters from the description
            props.put("query", Map.of("type", "string", "description", "The input for this tool"));
            params.put("properties", props);
            fn.setParameters(params);

            ChatCompletionRequest.Tool tool = new ChatCompletionRequest.Tool();
            tool.setType("function");
            tool.setFunction(fn);
            tools.add(tool);
        }
        log.debug("[OpenAiToolRegistry] Built {} tools for OpenAI API", tools.size());
        return tools;
    }

    /**
     * Lookup a tool by name from the underlying registry.
     */
    public ToolDefinition lookup(String name) {
        return toolRegistry.get(name);
    }

    public boolean isRegistered(String name) {
        return toolRegistry.isRegistered(name);
    }
}
