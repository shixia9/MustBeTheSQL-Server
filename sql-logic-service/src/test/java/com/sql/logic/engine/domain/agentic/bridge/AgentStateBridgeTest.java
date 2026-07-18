package com.sql.logic.engine.domain.agentic.bridge;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import com.sql.logic.engine.domain.agentic.core.ActionOutput;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentStateBridgeTest {

    private static OverAllState stateWith(Map<String, Object> data) {
        OverAllState state = mock(OverAllState.class);
        when(state.value(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return Optional.ofNullable(data.get(key));
        });
        when(state.value(anyString(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object def = inv.getArgument(1);
            Object val = data.get(key);
            return val != null ? val : def;
        });
        return state;
    }

    @Test
    void toAgentMessageShouldExtractInput() {
        OverAllState state = stateWith(Map.of(
                SqlAgentSpec.StateKey.INPUT, "查询所有用户"
        ));
        AgentMessage msg = AgentStateBridge.toAgentMessage(state);

        assertEquals("查询所有用户", msg.content());
        assertEquals("StateGraph", msg.senderName());
    }

    @Test
    void toAgentMessageShouldPreferRewriteQueryOverInput() {
        OverAllState state = stateWith(Map.of(
                SqlAgentSpec.StateKey.INPUT, "原始问题",
                SqlAgentSpec.StateKey.REWRITE_QUERY, "改写后的问题"
        ));
        AgentMessage msg = AgentStateBridge.toAgentMessage(state);

        assertEquals("改写后的问题", msg.content());
    }

    @Test
    void toAgentMessageShouldExtractSchemaContext() {
        OverAllState state = stateWith(Map.of(
                SqlAgentSpec.StateKey.INPUT, "test",
                SqlAgentSpec.StateKey.TABLE_RELATION, "DDL for users table",
                SqlAgentSpec.StateKey.DB_TYPE, "MySQL",
                SqlAgentSpec.StateKey.SCHEMA_NAME, "public"
        ));
        AgentMessage msg = AgentStateBridge.toAgentMessage(state);

        assertEquals("DDL for users table", msg.context().get("schemaInfo"));
        assertEquals("MySQL", msg.context().get("dialect"));
        assertEquals("public", msg.context().get("schemaName"));
    }

    @Test
    void toAgentMessageShouldExtractEvidenceAndMemory() {
        OverAllState state = stateWith(Map.of(
                SqlAgentSpec.StateKey.INPUT, "test",
                SqlAgentSpec.StateKey.EVIDENCE, "some evidence text",
                SqlAgentSpec.StateKey.USER_MEMORY, "user prefers short answers",
                SqlAgentSpec.StateKey.CONVERSATION_HISTORY, "previous turn"
        ));
        AgentMessage msg = AgentStateBridge.toAgentMessage(state);

        assertEquals("some evidence text", msg.context().get("evidence"));
        assertEquals("user prefers short answers", msg.context().get("userMemory"));
        assertEquals("previous turn", msg.context().get("conversationHistory"));
    }

    @Test
    void toAgentMessageShouldExtractIdentityFields() {
        OverAllState state = stateWith(Map.of(
                SqlAgentSpec.StateKey.INPUT, "test",
                SqlAgentSpec.StateKey.USER_ID, 42L,
                SqlAgentSpec.StateKey.CONNECTION_ID, 7L,
                SqlAgentSpec.StateKey.LLM_CONFIG_ID, 3L,
                SqlAgentSpec.StateKey.THREAD_ID, "thread-abc"
        ));
        AgentMessage msg = AgentStateBridge.toAgentMessage(state);

        assertEquals(42L, msg.context().get("userId"));
        assertEquals(7L, msg.context().get("connectionId"));
        assertEquals(3L, msg.context().get("llmConfigId"));
        assertEquals("thread-abc", msg.context().get("threadId"));
    }

    @Test
    void toStateUpdatesShouldMapSuccessOutput() {
        AgentMessage msg = AgentMessage.builder()
                .content("SELECT * FROM users")
                .success(true)
                .actionReport(ActionOutput.success("Query returned 5 rows"))
                .build();

        Map<String, Object> updates = AgentStateBridge.toStateUpdates(msg);

        assertEquals("SELECT * FROM users", updates.get(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT));
        assertEquals("Query returned 5 rows", updates.get(SqlAgentSpec.StateKey.SQL_EXECUTION_RESULT));
        assertEquals("", updates.get(SqlAgentSpec.StateKey.SQL_ERROR));
        assertEquals(true, updates.get("agentSuccess"));
    }

    @Test
    void toStateUpdatesShouldMapErrorOutput() {
        AgentMessage msg = AgentMessage.builder()
                .content("SELECT * FROM nonexistent")
                .success(false)
                .actionReport(ActionOutput.fail("Table not found"))
                .build();

        Map<String, Object> updates = AgentStateBridge.toStateUpdates(msg);

        assertEquals("Table not found", updates.get(SqlAgentSpec.StateKey.SQL_ERROR));
        assertEquals(false, updates.get("agentSuccess"));
    }

    @Test
    void toStateUpdatesShouldPropagateActionData() {
        ActionOutput report = ActionOutput.success("ok", Map.of(
                "sql", "SELECT 1",
                "columns", java.util.List.of("col1"),
                "rowCount", 10
        ));
        AgentMessage msg = AgentMessage.builder()
                .content("result")
                .actionReport(report)
                .build();

        Map<String, Object> updates = AgentStateBridge.toStateUpdates(msg);

        assertEquals("SELECT 1", updates.get(SqlAgentSpec.StateKey.SQL_GENERATION_RESULT));
        assertEquals(10, updates.get("executionRowCount"));
    }

    @Test
    void toAgentMessageShouldHandleEmptyState() {
        OverAllState state = stateWith(Map.of());
        AgentMessage msg = AgentStateBridge.toAgentMessage(state);

        assertEquals("", msg.content());
        assertNotNull(msg.context());
    }

    @Test
    void toAgentMessageShouldExtractAgentStudioConfig() {
        OverAllState state = stateWith(Map.of(
                SqlAgentSpec.StateKey.INPUT, "test",
                SqlAgentSpec.StateKey.AGENT_SYSTEM_PROMPT, "custom prompt",
                SqlAgentSpec.StateKey.AGENT_MEMORY_ENABLED, "true",
                SqlAgentSpec.StateKey.AGENT_NAME, "MyAgent"
        ));
        AgentMessage msg = AgentStateBridge.toAgentMessage(state);

        assertEquals("custom prompt", msg.context().get("agentSystemPrompt"));
        assertEquals("true", msg.context().get("agentMemoryEnabled"));
        assertEquals("MyAgent", msg.context().get("agentName"));
    }
}
