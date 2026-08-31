package com.sql.logic.engine.domain.agentic.hitl;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.sql.logic.engine.domain.agent.SqlAgentSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgenticHitlNodeTest {

    @Test
    void approvalRoutesBackToManager() {
        AgenticHitlNode node = new AgenticHitlNode();
        Map<String, Object> result = node.apply(stateWith(Map.of(
                SqlAgentSpec.StateKey.CONFIRMATION_APPROVED, true,
                SqlAgentSpec.StateKey.REPAIR_COUNT, 0
        )));

        assertEquals("APPROVED", result.get(SqlAgentSpec.StateKey.HITL_STATUS));
        assertEquals("MANAGER", result.get(SqlAgentSpec.StateKey.NEXT_NODE));
    }

    @Test
    void firstAndSecondRejectionsRequireReplanning() {
        AgenticHitlNode node = new AgenticHitlNode();

        Map<String, Object> first = node.apply(stateWith(Map.of(
                SqlAgentSpec.StateKey.CONFIRMATION_APPROVED, false,
                SqlAgentSpec.StateKey.CONFIRMATION_FEEDBACK, "请改成只读查询",
                SqlAgentSpec.StateKey.REPAIR_COUNT, 0
        )));
        assertEquals(1, first.get(SqlAgentSpec.StateKey.REPAIR_COUNT));
        assertEquals("REPLAN_REQUIRED", first.get(SqlAgentSpec.StateKey.HITL_STATUS));
        assertEquals("MANAGER", first.get(SqlAgentSpec.StateKey.NEXT_NODE));

        Map<String, Object> second = node.apply(stateWith(Map.of(
                SqlAgentSpec.StateKey.CONFIRMATION_APPROVED, false,
                SqlAgentSpec.StateKey.REPAIR_COUNT, 1
        )));
        assertEquals(2, second.get(SqlAgentSpec.StateKey.REPAIR_COUNT));
        assertEquals("REPLAN_REQUIRED", second.get(SqlAgentSpec.StateKey.HITL_STATUS));
    }

    @Test
    void thirdRejectionTerminatesWithoutManager() {
        AgenticHitlNode node = new AgenticHitlNode();
        Map<String, Object> result = node.apply(stateWith(Map.of(
                SqlAgentSpec.StateKey.CONFIRMATION_APPROVED, false,
                SqlAgentSpec.StateKey.REPAIR_COUNT, 2
        )));

        assertEquals(3, result.get(SqlAgentSpec.StateKey.REPAIR_COUNT));
        assertEquals("REJECTED", result.get(SqlAgentSpec.StateKey.HITL_STATUS));
        assertEquals(StateGraph.END, result.get(SqlAgentSpec.StateKey.NEXT_NODE));
    }

    private static OverAllState stateWith(Map<String, Object> data) {
        OverAllState state = mock(OverAllState.class);
        when(state.value(anyString())).thenAnswer(inv ->
                Optional.ofNullable(data.get(inv.getArgument(0))));
        when(state.value(anyString(), any())).thenAnswer(inv -> {
            Object value = data.get(inv.getArgument(0));
            return value == null ? inv.getArgument(1) : value;
        });
        return state;
    }
}
