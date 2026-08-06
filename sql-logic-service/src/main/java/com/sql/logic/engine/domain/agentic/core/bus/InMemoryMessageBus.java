package com.sql.logic.engine.domain.agentic.core.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * In-JVM implementation of {@link AgentMessageBus}.
 *
 * <p>Subscriptions are keyed by topic in a {@link ConcurrentHashMap} of
 * {@link CopyOnWriteArrayList}s — optimized for the read-heavy dispatch path (many
 * sends, comparatively few subscribes).
 */
public final class InMemoryMessageBus implements AgentMessageBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageBus.class);

    /** Wildcard topic: subscribers here receive every sent and broadcast message. */
    public static final String WILDCARD_TOPIC = "*";

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<HandlerEntry>> topicHandlers =
            new ConcurrentHashMap<>();
    private final Executor dispatcher;

    /** Default: async dispatch on virtual threads. */
    public InMemoryMessageBus() {
        this(Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Custom dispatcher — primarily for tests ({@code Runnable::run} runs handlers
     * synchronously on the calling thread, making assertions deterministic).
     */
    public InMemoryMessageBus(Executor dispatcher) {
        this.dispatcher = dispatcher == null ? Executors.newVirtualThreadPerTaskExecutor() : dispatcher;
    }

    @Override
    public void send(BusMessage message) {
        if (message == null) return;
        // Point-to-point: receiverName topic + wildcard subscribers.
        String topic = message.receiverName();
        dispatch(message, topic, false);
    }

    @Override
    public void broadcast(BusMessage message) {
        if (message == null) return;
        // Fan-out: every topic's subscribers.
        dispatch(message, null, true);
    }

    @Override
    public Subscription subscribe(String topic, Consumer<BusMessage> handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        String key = (topic == null || topic.isBlank()) ? WILDCARD_TOPIC : topic;
        HandlerEntry entry = new HandlerEntry(handler);
        topicHandlers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(entry);
        return entry;
    }

    /**
     * Dispatch {@code message} to matching handlers.
     *
     * @param topic          receiver topic for {@code send}; ignored when {@code broadcast}
     * @param includeWildcard whether wildcard {@code "*"} subscribers should also receive
     *                        (true for send, true for broadcast via the all-topics walk)
     * @param broadcast      if true, deliver to every topic
     */
    private void dispatch(BusMessage message, String topic, boolean broadcast) {
        if (broadcast) {
            // Iterate a snapshot of all topics; wildcard is included naturally.
            for (var entry : topicHandlers.entrySet()) {
                fire(entry.getValue(), message);
            }
        } else {
            // Point-to-point: exact topic + wildcard. A null/blank receiverName means
            // "no specific recipient" — only wildcard subscribers receive it.
            if (topic != null && !topic.isBlank()) {
                CopyOnWriteArrayList<HandlerEntry> exact = topicHandlers.get(topic);
                if (exact != null) {
                    fire(exact, message);
                }
            }
            if (!WILDCARD_TOPIC.equals(topic)) {
                CopyOnWriteArrayList<HandlerEntry> wild = topicHandlers.get(WILDCARD_TOPIC);
                if (wild != null) {
                    fire(wild, message);
                }
            }
        }
    }

    private void fire(CopyOnWriteArrayList<HandlerEntry> handlers, BusMessage message) {
        // Copy-on-write list: safe to iterate without external synchronization.
        for (HandlerEntry entry : handlers) {
            if (entry.cancelled.get()) continue;
            dispatcher.execute(() -> {
                try {
                    if (!entry.cancelled.get()) {
                        entry.handler.accept(message);
                    }
                } catch (Exception e) {
                    // Fault isolation: one handler's failure must not affect others.
                    log.warn("[InMemoryMessageBus] handler threw while processing {}: {}",
                            message.type(), e.toString());
                }
            });
        }
    }

    /** Package-private for tests: number of active handlers on a topic. */
    int handlerCount(String topic) {
        CopyOnWriteArrayList<HandlerEntry> list = topicHandlers.get(topic);
        return list == null ? 0 : (int) list.stream().filter(e -> !e.cancelled.get()).count();
    }

    /**
     * Internal subscription handle. Holds the handler and a cancellation flag; doubling as
     * the {@link Subscription} returned to callers. Removal from the list is lazy (cancelled
     * flag) to keep the hot dispatch path allocation-free and lock-free.
     */
    private static final class HandlerEntry implements Subscription {
        private final Consumer<BusMessage> handler;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        HandlerEntry(Consumer<BusMessage> handler) {
            this.handler = handler;
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }
}
