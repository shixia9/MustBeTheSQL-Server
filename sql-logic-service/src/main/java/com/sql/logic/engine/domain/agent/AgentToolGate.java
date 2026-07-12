package com.sql.logic.engine.domain.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
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

    @SuppressWarnings("unchecked")
    public static boolean isToolEnabled(OverAllState state, String toolKey) {
        Optional<Object> opt = state.value(SqlAgentSpec.StateKey.AGENT_TOOLS);
        if (opt.isEmpty()) {
            log.debug("[AgentToolGate] AGENT_TOOLS key absent from state — defaulting to enabled");
            return true; // key missing = all enabled (backward compatible)
        }
        Object toolsObj = opt.get();
        if (toolsObj == null) {
            log.debug("[AgentToolGate] AGENT_TOOLS is null — defaulting to enabled");
            return true;
        }
        if (toolsObj instanceof List<?> list) {
            if (list.isEmpty()) {
                return true; // empty list = all enabled (defaults fallback)
            }
            for (Object item : list) {
                // Use String.valueOf for robustness: state serialization may change
                // element types across node transitions in the StateGraph engine.
                if (toolKey.equals(String.valueOf(item))) {
                    return true;
                }
            }
            log.info("[AgentToolGate] Tool '{}' is DISABLED (enabled tools: {})", toolKey, list);
            return false;
        }
        log.warn("[AgentToolGate] AGENT_TOOLS has unexpected type: {} — defaulting to enabled",
                toolsObj.getClass().getName());
        return true; // unexpected type = all enabled (backward compatible)
    }
}
