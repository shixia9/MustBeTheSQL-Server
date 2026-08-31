package com.sql.logic.engine.domain.agentic.core.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    public CompletionStage<DeliveryReceipt> send(BusMessage message) {
        long startedAt = System.nanoTime();
        if (message == null) {
            return CompletableFuture.completedFuture(DeliveryReceipt.empty(null, 0L));
        }

        String topic = message.receiverName();
        List<HandlerEntry> exact = topic == null || topic.isBlank()
                ? List.of() : activeHandlers(topicHandlers.get(topic));

        // Wildcard subscribers are observers. They receive a best-effort copy but
        // are deliberately excluded from the business delivery receipt.
        if (!WILDCARD_TOPIC.equals(topic)) {
            fireBestEffort(topicHandlers.get(WILDCARD_TOPIC), message);
        }

        if (exact.isEmpty()) {
            return CompletableFuture.completedFuture(
                    DeliveryReceipt.empty(message, System.nanoTime() - startedAt));
        }

        List<CompletableFuture<Throwable>> attempts = new ArrayList<>(exact.size());
        for (HandlerEntry entry : exact) {
            attempts.add(fireTracked(entry, message));
        }
        return CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<String> errors = new ArrayList<>();
                    int succeeded = 0;
                    for (CompletableFuture<Throwable> attempt : attempts) {
                        Throwable error = attempt.join();
                        if (error == null) succeeded++;
                        else errors.add(error.toString());
                    }
                    return new DeliveryReceipt(message.messageId(), topic, exact.size(),
                            succeeded, exact.size() - succeeded, errors,
                            System.nanoTime() - startedAt);
                });
    }

    @Override
    public void broadcast(BusMessage message) {
        if (message == null) return;
        for (var entry : topicHandlers.entrySet()) {
            fireBestEffort(entry.getValue(), message);
        }
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

    private void fireBestEffort(CopyOnWriteArrayList<HandlerEntry> handlers, BusMessage message) {
        if (handlers == null) return;
        // Copy-on-write list: safe to iterate without external synchronization.
        for (HandlerEntry entry : handlers) {
            if (entry.cancelled.get()) continue;
            fireTracked(entry, message);
        }
    }

    private CompletableFuture<Throwable> fireTracked(HandlerEntry entry, BusMessage message) {
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        try {
            dispatcher.execute(() -> {
                try {
                    if (entry.cancelled.get()) {
                        outcome.complete(new IllegalStateException("subscription cancelled before delivery"));
                    } else {
                        entry.handler.accept(message);
                        outcome.complete(null);
                    }
                } catch (Throwable error) {
                    log.warn("[InMemoryMessageBus] handler threw while processing {}: {}",
                            message.type(), error.toString());
                    outcome.complete(error);
                }
            });
        } catch (Throwable error) {
            log.warn("[InMemoryMessageBus] dispatcher rejected {}: {}",
                    message.type(), error.toString());
            outcome.complete(error);
        }
        return outcome;
    }

    private static List<HandlerEntry> activeHandlers(CopyOnWriteArrayList<HandlerEntry> handlers) {
        if (handlers == null || handlers.isEmpty()) return List.of();
        return handlers.stream().filter(entry -> !entry.cancelled.get()).toList();
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
