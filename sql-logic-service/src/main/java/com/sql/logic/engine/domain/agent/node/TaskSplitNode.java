package com.sql.logic.engine.domain.agent.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.AgentStateUtil;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.ha.LlmCallReporter;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.trace.TraceContext;
import com.sql.logic.engine.infrastructure.dao.AgentSubtaskDao;
import com.sql.logic.engine.infrastructure.po.AgentSubtask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task Split Node (Phase B) — decomposes a COMPLEX query into subtasks.
 * <p>
 * Renders {@code task-split.st} with the rewrite query, recalled schema,
 * and evidence text, then asks the LLM to produce a JSON array of subtask
 * objects. Each subtask is persisted via {@link AgentSubtaskDao} for audit
 * and the full array is stored in state as {@code SUBTASKS} (JSON string).
 * <p>
 * The cursor {@code CURRENT_SUBTASK} is initialised to 1 and
 * {@code SUBTASK_RESULTS} is initialised to empty.
 */
@Component
public class TaskSplitNode implements NodeAction {

    private static final Logger log = LoggerFactory.getLogger(TaskSplitNode.class);

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper;
    private final AgentSubtaskDao agentSubtaskDao;
    private final LlmCallReporter llmCallReporter;

    public TaskSplitNode(LlmClientManager llmClientManager,
                         PromptManager promptManager,
                         ObjectMapper objectMapper,
                         AgentSubtaskDao agentSubtaskDao,
                         LlmCallReporter llmCallReporter) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
        this.objectMapper = objectMapper;
        this.agentSubtaskDao = agentSubtaskDao;
        this.llmCallReporter = llmCallReporter;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String rewriteQuery = state.value(SqlAgentSpec.StateKey.REWRITE_QUERY, "");
        if (rewriteQuery == null || rewriteQuery.isBlank()) {
            rewriteQuery = state.value(SqlAgentSpec.StateKey.INPUT, "");
        }
        String tableRelation = state.value(SqlAgentSpec.StateKey.TABLE_RELATION, "");
        String evidence = state.value(SqlAgentSpec.StateKey.EVIDENCE, "");

        Long llmConfigId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.LLM_CONFIG_ID, (Long) null));
        Long userId = AgentStateUtil.toLong(state.value(SqlAgentSpec.StateKey.USER_ID, (Long) null));

        String schemaSummary = (tableRelation == null || tableRelation.isBlank()) ? "（无召回的 Schema）" : tableRelation;
        String evidenceSummary = (evidence == null || evidence.isBlank()) ? "无" : evidence;

        String prompt = promptManager.render(SqlAgentSpec.PromptName.TASK_SPLIT, Map.of(
                "rewrite_query", rewriteQuery,
                "schema_summary", schemaSummary,
                "evidence_summary", evidenceSummary
        ));

        LLMStrategy strategy = llmClientManager.resolveTraced(llmConfigId, userId,
                (TraceContext) state.value(SqlAgentSpec.StateKey.TRACE_CONTEXT).orElse(null),
                SqlAgentSpec.Node.TASK_SPLIT, llmCallReporter);
        String raw = strategy.generateSql(prompt, null);
        String response = raw == null ? "" : raw.trim();

        List<Map<String, Object>> subtasks = parseSubtasks(response);
        String subtasksJson = objectMapper.writeValueAsString(subtasks);
        log.info("[TaskSplitNode] Produced {} subtasks", subtasks.size());

        String threadId = state.value(SqlAgentSpec.StateKey.THREAD_ID, "");
        if (threadId != null && !threadId.isBlank() && userId != null) {
            try {
                int seq = 1;
                for (Map<String, Object> subtask : subtasks) {
                    AgentSubtask po = new AgentSubtask();
                    po.setThreadId(threadId);
                    po.setUserId(userId);
                    po.setSeq(seq++);
                    po.setInstruction(subtask.get("instruction") instanceof String s ? s : "");
                    po.setStatus("PENDING");
                    po.setCreateTime(LocalDateTime.now());
                    po.setUpdateTime(LocalDateTime.now());
                    agentSubtaskDao.insert(po);
                }
            } catch (Exception e) {
                log.warn("[TaskSplitNode] Failed to persist subtasks: {}", e.getMessage(), e);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(SqlAgentSpec.StateKey.SUBTASKS, subtasksJson);
        out.put(SqlAgentSpec.StateKey.CURRENT_SUBTASK, 1);
        out.put(SqlAgentSpec.StateKey.SUBTASK_RESULTS, "");
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSubtasks(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("(?s)^```(?:\\w+)?\\s*\\n?", "")
                    .replaceAll("\\n?```\\s*$", "")
                    .trim();
        }
        try {
            Object parsed = objectMapper.readValue(cleaned, Object.class);
            if (parsed instanceof List) {
                List<Object> rawList = (List<Object>) parsed;
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object item : rawList) {
                    if (item instanceof Map) {
                        result.add((Map<String, Object>) item);
                    }
                }
                return result;
            }
            if (parsed instanceof Map) {
                return List.of((Map<String, Object>) parsed);
            }
        } catch (Exception e) {
            log.debug("[TaskSplitNode] JSON parse failed: {}", e.getMessage());
        }
        return List.of();
    }
}
