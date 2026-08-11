package com.sql.logic.engine.domain.agentic.core.bus;

import java.util.function.Consumer;

/**
 * Inter-Agent message bus abstraction.
 *
 * <p>Replaces the implicit shared-memory coupling (agents reading/writing a shared
 * {@code OverAllState}) with an explicit, subscription-based messaging contract. Agents
 * {@link #subscribe subscribe} to a topic (conventionally the agent name) and either
 * {@link #send send} point-to-point messages or {@link #broadcast broadcast} to all
 * subscribers.
 *
 * <p>Two implementations are planned:
 * <ul>
 *   <li>{@link InMemoryMessageBus} — single-JVM, default</li>
 *   <li>{@code RedisMessageBus} — cross-instance, persistent</li>
 * </ul>
 *
 * <h2>Topic semantics</h2>
 * <ul>
 *   <li>{@link #send} delivers the message to subscribers of the topic equal to
 *       {@code message.receiverName()}, plus any subscribers on the wildcard topic
 *       {@code "*"}.</li>
 *   <li>{@link #broadcast} delivers to <em>all</em> subscribers regardless of topic.</li>
 *   <li>Topic {@code "*"} is a wildcard — its subscribers receive every sent and
 *       broadcast message.</li>
 * </ul>
 *
 * <p>Implementations MUST guarantee: thread-safe subscribe/send/broadcast; per-handler
 * fault isolation (one handler throwing MUST NOT block others); non-blocking dispatch.
 */
public interface AgentMessageBus {

    /**
     * Point-to-point delivery to subscribers of {@code message.receiverName()} (and
     * wildcard {@code "*"} subscribers).
     */
    void send(BusMessage message);

    /**
     * Fan-out delivery to <em>all</em> subscribers across every topic.
     */
    void broadcast(BusMessage message);

    /**
     * Subscribe a handler to a topic. Returns a {@link Subscription} that can be
     * {@link Subscription#cancel() cancelled} to stop delivery and release resources.
     *
     * @param topic   topic name (agent name), or {@code "*"} for all messages
     * @param handler invoked for each matching message
     * @return cancellation handle
     */
    Subscription subscribe(String topic, Consumer<BusMessage> handler);

    /**
     * Cancellation handle returned by {@link #subscribe}. Calling {@link #cancel()}
     * removes the handler so it receives no further messages. Idempotent.
     */
    interface Subscription {
        void cancel();
    }
}
