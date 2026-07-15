package com.sql.logic.engine.domain.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.sql.logic.engine.domain.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Central gate for Agent Studio tool switches.
 * <p>
 * Phase C: reads {@code AGENT_TOOLS} list from state and answers whether a
 * given tool key ({@code sql}, {@code schema}, {@code python}, {@code sample})
 * is enabled for the current run. Nodes call this before performing work that
 * the user has chosen to disable.
 * <p>
 * Phase D1: delegates tool key validation to {@link ToolRegistry} so that
 * newly registered tools (including MCP tools) are automatically recognised
 * without code changes in this class.
 * <p>
 * When the state key is missing or unreadable the gate defaults to
 * {@code true} — backward compatible with runs that predate Agent Studio.
 */
public final class AgentToolGate {

    private static final Logger log = LoggerFactory.getLogger(AgentToolGate.class);

    private AgentToolGate() {}

    public static final String TOOL_SQL    = "sql";
    public static final String TOOL_SCHEMA = "schema";
    public static final String TOOL_PYTHON = "python";
    public static final String TOOL_SAMPLE = "sample";

    /**
     * Check whether {@code toolKey} is enabled in the current agent run.
     * <p>
     * When {@code registry} is non-null the key is first validated against
     * the registry — unknown keys are logged and treated as disabled.
     */
    public static boolean isToolEnabled(OverAllState state, String toolKey, ToolRegistry registry) {
        // Phase D1: validate against registry if available
        if (registry != null && !registry.isRegistered(toolKey)) {
            log.debug("[AgentToolGate] Unknown tool key '{}' — treating as disabled", toolKey);
            return false;
        }
        Optional<Object> opt = state.value(SqlAgentSpec.StateKey.AGENT_TOOLS);
        if (opt.isEmpty()) {
            return true; // key missing = all enabled (backward compatible)
        }
        Object toolsObj = opt.get();
        if (toolsObj == null) {
            return true;
        }
        if (toolsObj instanceof List<?> list) {
            if (list.isEmpty()) {
                return true;
            }
            for (Object item : list) {
                if (toolKey.equals(String.valueOf(item))) {
                    return true;
                }
            }
            log.info("[AgentToolGate] Tool '{}' is DISABLED (enabled tools: {})", toolKey, list);
            return false;
        }
        log.warn("[AgentToolGate] AGENT_TOOLS has unexpected type: {} — defaulting to enabled",
                toolsObj.getClass().getName());
        return true;
    }

    /** Backward-compatible overload without registry validation. */
    public static boolean isToolEnabled(OverAllState state, String toolKey) {
        return isToolEnabled(state, toolKey, null);
    }
}
