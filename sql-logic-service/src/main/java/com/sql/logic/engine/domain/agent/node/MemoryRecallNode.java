package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.memory.MemoryDomainService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MemoryRecallNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(MemoryRecallNode.class);

    private static final int TOP_K = 5;

    private final MemoryDomainService memoryDomainService;

    public MemoryRecallNode(MemoryDomainService memoryDomainService) {
        this.memoryDomainService = memoryDomainService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String userInput = state.value(SqlAgentSpec.StateKey.INPUT, "");
        Long userId = AgentStateUtil.toLong(
                state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));

        Map<String, Object> result = new LinkedHashMap<>();

        // Phase B (B4): honour the Agent Studio memory switch — skip recall when disabled.
        Object memEnabled = state.value(SqlAgentSpec.StateKey.AGENT_MEMORY_ENABLED, Boolean.TRUE);
        boolean memoryEnabled = !(memEnabled instanceof Boolean b) || b;
        if (!memoryEnabled) {
            result.put(SqlAgentSpec.StateKey.USER_MEMORY, "");
            log.debug("[MemoryRecallNode] Memory disabled by Agent config, skipping recall");
            return result;
        }

        try {
            List<Map<String, Object>> memories = memoryDomainService.searchRelevant(
                    userId, userInput, TOP_K);

            if (memories == null || memories.isEmpty()) {
                result.put(SqlAgentSpec.StateKey.USER_MEMORY, "");
                return result;
            }

            StringBuilder sb = new StringBuilder("[记忆要点]\n");
            for (Map<String, Object> mem : memories) {
                String type = String.valueOf(mem.getOrDefault("type", ""));
                String content = String.valueOf(mem.getOrDefault("content", ""));
                sb.append("- [").append(type).append("] ").append(content).append("\n");
            }
            result.put(SqlAgentSpec.StateKey.USER_MEMORY, sb.toString().trim());
            log.info("[MemoryRecallNode] Recalled {} memories for userId={}", memories.size(), userId);
        } catch (Exception e) {
            log.warn("[MemoryRecallNode] Memory recall failed: {}", e.getMessage());
            result.put(SqlAgentSpec.StateKey.USER_MEMORY, "");
        }

        return result;
    }
}
