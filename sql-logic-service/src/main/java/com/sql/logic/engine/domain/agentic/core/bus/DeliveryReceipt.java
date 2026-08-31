package com.sql.logic.engine.domain.agentic.core.bus;

import java.util.List;

/**
 * Result of handing one point-to-point message to the in-process bus handlers.
 *
 * <p>This is a transport-level receipt only. A successful receipt means that a
 * receiver callback returned normally; it does not mean that the worker accepted
 * or completed the business task. Those two stages are represented by
 * {@link BusMessage.TaskAck}/{@link BusMessage.TaskNack} and
 * {@link BusMessage.ToolResult}, respectively.
 */
public record DeliveryReceipt(
        String messageId,
        String receiverName,
        int matchedReceivers,
        int successfulReceivers,
        int failedReceivers,
        List<String> errors,
        long deliveryLatencyNanos
) {
    public DeliveryReceipt {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean delivered() {
        return successfulReceivers > 0;
    }

    static DeliveryReceipt empty(BusMessage message, long latencyNanos) {
        return new DeliveryReceipt(
                message != null ? message.messageId() : null,
                message != null ? message.receiverName() : null,
                0, 0, 0, List.of(), latencyNanos);
    }
}
