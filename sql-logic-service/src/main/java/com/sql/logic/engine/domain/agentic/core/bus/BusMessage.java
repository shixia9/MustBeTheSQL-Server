package com.sql.logic.engine.domain.agentic.core.bus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Standardized inter-Agent message protocol for the message-bus communication model.
 *
 * <p>This {@code sealed interface} defines a closed, compile-time-exhaustive set of
 * message types exchanged over {@link AgentMessageBus}. Each subtype is an immutable
 * {@code record} carrying a shared {@link BusHeader} (identity + routing) plus its own
 * type-specific payload. A {@code switch} over {@code BusMessage} benefits from Java's
 * sealed-type exhaustiveness checking — the compiler rejects any switch that misses a
 * permitted subtype.
 *
 * <h2>Identity contract</h2>
 * Every {@code BusMessage} exposes (through {@link #header()}):
 * <ul>
 *   <li>{@code messageId} — non-null unique id (auto-generated UUID when unset)</li>
 *   <li>{@code correlationId} — nullable; links a response to the request that caused it</li>
 *   <li>{@code timestamp} — non-null {@link Instant} (auto-set to now() when unset)</li>
 *   <li>{@code senderName} / {@code receiverName} — routing; {@code receiverName=null}
 *       on a {@link AgentMessageBus#broadcast} message</li>
 * </ul>
 *
 * <h2>Permitted subtypes (≥8)</h2>
 * {@link PlanProposal}, {@link TaskDispatch}, {@link ToolResult}, {@link ReviewRequest},
 * {@link ReviewResponse}, {@link StatusUpdate}, {@link ErrorReport}, {@link Shutdown}.
 */
public sealed interface BusMessage
        permits BusMessage.PlanProposal, BusMessage.TaskDispatch, BusMessage.ToolResult,
                BusMessage.ReviewRequest, BusMessage.ReviewResponse, BusMessage.StatusUpdate,
                BusMessage.ErrorReport, BusMessage.Shutdown {

    /** Shared identity + routing envelope shared by every message type. */
    BusHeader header();

    default String messageId() { return header().messageId(); }

    default String correlationId() { return header().correlationId(); }

    default Instant timestamp() { return header().timestamp(); }

    default String senderName() { return header().senderName(); }

    default String receiverName() { return header().receiverName(); }

    /** Human-readable type tag (simple class name) for logging/metrics. */
    default String type() { return getClass().getSimpleName(); }

    // ------------------------------------------------------------------
    //  Permitted message subtypes
    // ------------------------------------------------------------------

    /** Planner publishes a proposed plan for the team to review. */
    record PlanProposal(BusHeader header, String plan, List<String> steps) implements BusMessage {
        public PlanProposal {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** Manager dispatches a task to a specific worker agent. */
    record TaskDispatch(BusHeader header, String targetAgent, String task) implements BusMessage {}

    /** A worker reports the outcome of a tool invocation. */
    record ToolResult(BusHeader header, String toolName, boolean success, String result) implements BusMessage {
        public ToolResult {
            toolName = toolName == null ? "" : toolName;
            result = result == null ? "" : result;
        }
    }

    /** Request a review of an artifact (e.g. generated SQL, plan). */
    record ReviewRequest(BusHeader header, String artifact, String question) implements BusMessage {}

    /** Response to a {@link ReviewRequest} — approval decision + comments. */
    record ReviewResponse(BusHeader header, boolean approved, String comments) implements BusMessage {
        public ReviewResponse {
            comments = comments == null ? "" : comments;
        }
    }

    /** Generic status/progress update broadcast to interested teammates. */
    record StatusUpdate(BusHeader header, String status, Map<String, Object> details) implements BusMessage {
        public StatusUpdate {
            details = details == null ? Map.of() : Map.copyOf(details);
        }
    }

    /** Structured error report — feeds REQ-06 ErrorClassifier when integrated. */
    record ErrorReport(BusHeader header, String errorCode, String message) implements BusMessage {
        public ErrorReport {
            errorCode = errorCode == null ? "UNKNOWN" : errorCode;
            message = message == null ? "" : message;
        }
    }

    /** Polite shutdown signal — a teammate may decline if work is unfinished. */
    record Shutdown(BusHeader header, String reason) implements BusMessage {
        public Shutdown {
            reason = reason == null ? "" : reason;
        }
    }

    // ------------------------------------------------------------------
    //  BusHeader — identity + routing envelope
    // ------------------------------------------------------------------

    /**
     * Immutable identity + routing envelope. Use {@link #builder()} which auto-fills
     * {@code messageId} (UUID) and {@code timestamp} (now) when omitted.
     */
    record BusHeader(String messageId, String correlationId, Instant timestamp,
                     String senderName, String receiverName) {

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String messageId;
            private String correlationId;
            private Instant timestamp;
            private String senderName;
            private String receiverName;

            public Builder messageId(String v) { this.messageId = v; return this; }
            public Builder correlationId(String v) { this.correlationId = v; return this; }
            public Builder timestamp(Instant v) { this.timestamp = v; return this; }
            public Builder senderName(String v) { this.senderName = v; return this; }
            public Builder receiverName(String v) { this.receiverName = v; return this; }

            public BusHeader build() {
                return new BusHeader(
                        messageId != null ? messageId : UUID.randomUUID().toString(),
                        correlationId,
                        timestamp != null ? timestamp : Instant.now(),
                        senderName,
                        receiverName);
            }
        }
    }
}
