package com.sql.logic.engine.domain.agentic.core.bus;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-01 AC-1 (sealed exhaustive message types) + AC-2 (identity contract).
 */
class BusMessageTest {

    @Test
    void shouldExposeAllEightPermittedSubtypes() {
        // AC-1: the sealed interface permits exactly the 8 documented message types.
        BusMessage msg = new BusMessage.TaskDispatch(header("Manager", "DataScientist"), "DataScientist", "analyze");
        // A switch expression over a sealed type is exhaustive — the compiler rejects
        // this switch if any permitted subtype is missing. This is the compile-time
        // guarantee required by AC-1.
        String label = switch (msg) {
            case BusMessage.PlanProposal ignored -> "plan";
            case BusMessage.TaskDispatch ignored -> "dispatch";
            case BusMessage.ToolResult ignored -> "tool";
            case BusMessage.ReviewRequest ignored -> "rev-req";
            case BusMessage.ReviewResponse ignored -> "rev-res";
            case BusMessage.StatusUpdate ignored -> "status";
            case BusMessage.ErrorReport ignored -> "error";
            case BusMessage.Shutdown ignored -> "shutdown";
        };
        assertEquals("dispatch", label);
    }

    @Test
    void everySubtypeShouldBeExhaustivelyMatchable() {
        // AC-1: each of the 8 subtypes is constructible and matchable.
        BusMessage[] messages = {
                new BusMessage.PlanProposal(header("P", "M"), "plan", List.of("a", "b")),
                new BusMessage.TaskDispatch(header("M", "D"), "D", "task"),
                new BusMessage.ToolResult(header("D", "M"), "sql", true, "ok"),
                new BusMessage.ReviewRequest(header("D", "M"), "sql", "is this safe?"),
                new BusMessage.ReviewResponse(header("M", "D"), true, "approved"),
                new BusMessage.StatusUpdate(header("D", "*"), "running", Map.of("step", 2)),
                new BusMessage.ErrorReport(header("D", "M"), "TIMEOUT", "llm timed out"),
                new BusMessage.Shutdown(header("M", "D"), "done")
        };
        assertEquals(8, messages.length);
        for (BusMessage m : messages) {
            assertNotNull(m.type());
            assertNotNull(m.header());
        }
    }

    @Test
    void headerShouldAutoFillMessageIdAndTimestampWhenUnset() {
        // AC-2: messageId + timestamp are non-null even when the builder omits them.
        BusMessage.BusHeader h = BusMessage.BusHeader.builder()
                .senderName("Manager")
                .receiverName("DataScientist")
                .build();
        assertNotNull(h.messageId());
        assertNotNull(h.timestamp());
        // correlationId is intentionally nullable.
        assertNull(h.correlationId());
    }

    @Test
    void headerShouldPreserveExplicitlySetIdentity() {
        Instant fixed = Instant.parse("2026-08-06T10:15:30Z");
        BusMessage.BusHeader h = BusMessage.BusHeader.builder()
                .messageId("msg-123")
                .correlationId("req-456")
                .timestamp(fixed)
                .senderName("Manager")
                .receiverName("DataScientist")
                .build();
        assertEquals("msg-123", h.messageId());
        assertEquals("req-456", h.correlationId());
        assertEquals(fixed, h.timestamp());
    }

    @Test
    void messageDelegatesShouldReturnHeaderValues() {
        // AC-2: every BusMessage exposes the identity contract via header delegation.
        BusMessage msg = new BusMessage.ToolResult(
                BusMessage.BusHeader.builder()
                        .messageId("t-1").correlationId("c-1")
                        .senderName("DataScientist").receiverName("Manager")
                        .build(),
                "sql", true, "rows=10");
        assertEquals("t-1", msg.messageId());
        assertEquals("c-1", msg.correlationId());
        assertNotNull(msg.timestamp());
        assertEquals("DataScientist", msg.senderName());
        assertEquals("Manager", msg.receiverName());
        assertEquals("ToolResult", msg.type());
    }

    @Test
    void recordsShouldBeImmutable() {
        BusMessage.StatusUpdate update = new BusMessage.StatusUpdate(
                header("D", "M"), "running", Map.of("step", 1));
        // Map is defensively copied — mutating the source must not affect the record.
        Map<String, Object> src = new java.util.HashMap<>(Map.of("step", 2));
        BusMessage.StatusUpdate u2 = new BusMessage.StatusUpdate(header("D", "M"), "running", src);
        src.put("step", 99);
        assertEquals(2, u2.details().get("step"));
        assertThrows(UnsupportedOperationException.class, () -> u2.details().put("x", 1));
    }

    private BusMessage.BusHeader header(String sender, String receiver) {
        return BusMessage.BusHeader.builder().senderName(sender).receiverName(receiver).build();
    }
}
