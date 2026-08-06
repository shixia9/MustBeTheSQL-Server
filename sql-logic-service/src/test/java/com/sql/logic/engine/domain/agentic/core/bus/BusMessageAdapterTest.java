package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.ActionOutput;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link BusMessageAdapter} round-trips AgentMessage ↔ envelope JSON
 * and builds correctly-addressed BusMessage instances. Covers REQ-02 SWITCH
 * transport fidelity (the bus must carry goal/context/rely + reply/actionReport
 * without loss).
 */
class BusMessageAdapterTest {

    @Test
    void goalEnvelopeShouldRoundTripContentContextAndRelyMessages() {
        AgentMessage rely = AgentMessage.builder()
                .content("upstream question")
                .messageType(AgentMessage.MessageType.USER)
                .build();
        AgentMessage goal = AgentMessage.builder()
                .content("count users")
                .currentGoal("count users")
                .senderName("Manager")
                .senderRole("编排管理者")
                .rounds(2)
                .messageType(AgentMessage.MessageType.USER)
                .putContext("connectionId", 42L)
                .putContext("schemaDdl", "CREATE TABLE t(id INT)")
                .build();

        String json = BusMessageAdapter.encodeGoalEnvelope(goal, List.of(rely));
        BusMessageAdapter.DecodedGoal decoded = BusMessageAdapter.decodeGoalEnvelope(json);

        assertEquals("count users", decoded.goal().content());
        assertEquals("Manager", decoded.goal().senderName());
        assertEquals(2, decoded.goal().rounds());
        assertEquals(AgentMessage.MessageType.USER, decoded.goal().messageType());
        assertEquals(42L, ((Number) decoded.goal().context().get("connectionId")).longValue());
        assertEquals("CREATE TABLE t(id INT)", decoded.goal().context().get("schemaDdl"));
        assertEquals(1, decoded.relyMessages().size());
        assertEquals("upstream question", decoded.relyMessages().get(0).content());
    }

    @Test
    void replyEnvelopeShouldRoundTripSuccessAndActionReport() {
        AgentMessage reply = AgentMessage.builder()
                .content("SELECT COUNT(*) FROM users")
                .success(true)
                .rounds(3)
                .messageType(AgentMessage.MessageType.AI)
                .actionReport(new ActionOutput(true, "rows=5",
                        Map.of("rows", List.of(Map.of("c", 5))), List.of(), false))
                .build();

        String json = BusMessageAdapter.encodeReplyEnvelope(reply);
        AgentMessage decoded = BusMessageAdapter.decodeReplyEnvelope(json);

        assertTrue(decoded.success());
        assertEquals("SELECT COUNT(*) FROM users", decoded.content());
        assertNotNull(decoded.actionReport());
        assertTrue(decoded.actionReport().isExeSuccess());
        assertEquals("rows=5", decoded.actionReport().content());
        assertEquals(5, ((Number) ((Map<?, ?>) ((List<?>) decoded.actionReport().data().get("rows")).get(0)).get("c")).intValue());
    }

    @Test
    void failedReplyShouldRoundTripWithoutActionReport() {
        AgentMessage reply = AgentMessage.builder()
                .content("syntax error")
                .success(false)
                .build();
        AgentMessage decoded = BusMessageAdapter.decodeReplyEnvelope(BusMessageAdapter.encodeReplyEnvelope(reply));
        assertFalse(decoded.success());
        assertEquals("syntax error", decoded.content());
    }

    @Test
    void nonJsonSafeContextValueShouldCoerceToStringNotThrow() {
        Object evil = new Object() { @Override public String toString() { return "STRINGIFIED"; } };
        AgentMessage goal = AgentMessage.builder()
                .content("q")
                .putContext("nasty", evil)
                .build();
        // Must not throw — the nasty value is coerced to its toString.
        String json = assertDoesNotThrow(() -> BusMessageAdapter.encodeGoalEnvelope(goal, null));
        BusMessageAdapter.DecodedGoal decoded = BusMessageAdapter.decodeGoalEnvelope(json);
        assertEquals("STRINGIFIED", decoded.goal().context().get("nasty"));
    }

    @Test
    void toTaskDispatchShouldAddressAndCorrelateCorrectly() {
        BusMessage.TaskDispatch td = BusMessageAdapter.toTaskDispatch(
                "Manager", "DataScientist", "corr-1", "{}");
        assertInstanceOf(BusMessage.TaskDispatch.class, td);
        assertEquals("Manager", td.senderName());
        assertEquals("DataScientist", td.receiverName());
        assertEquals("DataScientist", td.targetAgent());
        assertEquals("corr-1", td.correlationId());
        assertEquals("{}", td.task());
    }

    @Test
    void toToolResultShouldAddressAndCorrelateCorrectly() {
        BusMessage.ToolResult tr = BusMessageAdapter.toToolResult(
                "DataScientist", "Manager", "corr-1", true, "{\"content\":\"ok\"}");
        assertInstanceOf(BusMessage.ToolResult.class, tr);
        assertEquals("DataScientist", tr.senderName());
        assertEquals("Manager", tr.receiverName());
        assertEquals("corr-1", tr.correlationId());
        assertTrue(tr.success());
    }

    @Test
    void errorEnvelopeShouldDecodeAsFailedReply() {
        String json = BusMessageAdapter.encodeErrorEnvelope(new RuntimeException("boom"));
        AgentMessage decoded = BusMessageAdapter.decodeReplyEnvelope(json);
        assertFalse(decoded.success());
        assertEquals("boom", decoded.content());
    }
}
