package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool discovery endpoint.
 * <p>
 * Returns metadata for all registered tools so the Agent Studio frontend
 * can render tool cards with descriptions directly from the registry rather
 * than hard-coded labels.
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> listTools() {
        List<Map<String, Object>> result = toolRegistry.listTools().stream().map(tool -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", tool.name());
            m.put("displayName", tool.displayName());
            m.put("description", tool.description());
            m.put("type", tool.type().name());
            m.put("parametersSchema", tool.parametersSchema());
            return m;
        }).toList();
        return Result.success(result);
    }
}
