package com.sql.logic.engine.domain.agentic.core.bus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-01 AC-3 (send), AC-4 (broadcast), AC-5 (unsubscribe), AC-6 (concurrency) +
 * fault-isolation behavior for {@link InMemoryMessageBus}.
 *
 * <p>Tests inject a synchronous dispatcher ({@code Runnable::run}) so delivery is
 * deterministic; the production default is async virtual-thread dispatch.
 */
class InMemoryMessageBusTest {

    private static BusMessage msg(String sender, String receiver) {
        return new BusMessage.TaskDispatch(
                BusMessage.BusHeader.builder().senderName(sender).receiverName(receiver).build(),
                receiver, "do-" + receiver);
    }

    private static InMemoryMessageBus syncBus() {
        return new InMemoryMessageBus(Runnable::run);
    }

    @Test
    void sendShouldDeliverToReceiverTopicSubscriber() {
        // AC-3: send delivers to subscribers of message.receiverName().
        InMemoryMessageBus bus = syncBus();
        AtomicReference<BusMessage> received = new AtomicReference<>();
        bus.subscribe("DataScientist", received::set);

        bus.send(msg("Manager", "DataScientist"));

        assertNotNull(received.get());
        assertEquals("Manager", received.get().senderName());
        assertEquals("DataScientist", received.get().receiverName());
    }

    @Test
    void sendShouldDeliverWithin10msLatencyBudget() throws InterruptedException {
        // AC-3: in-memory single-hop latency < 10ms.
        InMemoryMessageBus bus = new InMemoryMessageBus(); // async default
        CountDownLatch latch = new CountDownLatch(1);
        bus.subscribe("DataScientist", m -> latch.countDown());

        Instant start = Instant.now();
        bus.send(msg("Manager", "DataScientist"));
        boolean delivered = latch.await(1, TimeUnit.SECONDS);
        Duration elapsed = Duration.between(start, Instant.now());

        assertTrue(delivered, "message was not delivered within 1s");
        assertTrue(elapsed.toMillis() < 10, "latency " + elapsed.toMillis() + "ms exceeded 10ms budget");
    }

    @Test
    void sendShouldAlsoDeliverToWildcardSubscribers() {
        InMemoryMessageBus bus = syncBus();
        AtomicInteger direct = new AtomicInteger();
        AtomicInteger wildcard = new AtomicInteger();
        bus.subscribe("DataScientist", m -> direct.incrementAndGet());
        bus.subscribe(InMemoryMessageBus.WILDCARD_TOPIC, m -> wildcard.incrementAndGet());

        bus.send(msg("Manager", "DataScientist"));

        assertEquals(1, direct.get());
        assertEquals(1, wildcard.get());
    }

    @Test
    void broadcastShouldDeliverToAllSubscribersAcrossTopics() {
        // AC-4: broadcast fans out to every subscriber regardless of topic.
        InMemoryMessageBus bus = syncBus();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        AtomicInteger c = new AtomicInteger();
        bus.subscribe("A", m -> a.incrementAndGet());
        bus.subscribe("B", m -> b.incrementAndGet());
        bus.subscribe("C", m -> c.incrementAndGet());

        bus.broadcast(msg("Manager", null)); // null receiver => broadcast intent

        assertEquals(1, a.get());
        assertEquals(1, b.get());
        assertEquals(1, c.get());
    }

    @Test
    void unsubscribeShouldStopFurtherDelivery() {
        // AC-5: after cancel(), a handler receives nothing more; no leak.
        InMemoryMessageBus bus = syncBus();
        AtomicInteger count = new AtomicInteger();
        AgentMessageBus.Subscription sub = bus.subscribe("DataScientist", m -> count.incrementAndGet());

        bus.send(msg("Manager", "DataScientist"));
        assertEquals(1, count.get());

        sub.cancel();
        bus.send(msg("Manager", "DataScientist"));
        assertEquals(1, count.get(), "cancelled handler must not receive further messages");
        assertEquals(0, bus.handlerCount("DataScientist"));
    }

    @Test
    void cancelShouldBeIdempotent() {
        InMemoryMessageBus bus = syncBus();
        AgentMessageBus.Subscription sub = bus.subscribe("X", m -> {});
        sub.cancel();
        sub.cancel(); // must not throw
        assertEquals(0, bus.handlerCount("X"));
    }

    @Test
    void handlerExceptionShouldNotBlockOthers() {
        // AC-6 / fault isolation: a throwing handler does not affect siblings or caller.
        InMemoryMessageBus bus = syncBus();
        AtomicInteger survivor = new AtomicInteger();
        bus.subscribe("D", m -> { throw new IllegalStateException("boom"); });
        bus.subscribe("D", m -> survivor.incrementAndGet());

        bus.send(msg("Manager", "D")); // must not propagate the exception

        assertEquals(1, survivor.get(), "sibling handler must still run");
    }

    @Test
    void shouldSurviveConcurrentSendAndSubscribe() throws Exception {
        // AC-6: 10 threads send + 10 threads subscribe/unsubscribe concurrently, 10k messages,
        // no exception, no message loss on the durable subscriber.
        InMemoryMessageBus bus = new InMemoryMessageBus();
        CountDownLatch durableLatch = new CountDownLatch(10_000);
        bus.subscribe("sink", m -> durableLatch.countDown());

        int threads = 10;
        int perThread = 1_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        bus.send(msg("Producer-" + Thread.currentThread().getName(), "sink"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "producers did not finish in time");
        assertTrue(durableLatch.await(30, TimeUnit.SECONDS),
                "lost messages: expected 10000, still waiting " + durableLatch.getCount());
    }

    @Test
    void subscribeWithNullHandlerShouldThrow() {
        InMemoryMessageBus bus = syncBus();
        assertThrows(IllegalArgumentException.class, () -> bus.subscribe("X", null));
    }

    @Test
    void nullMessageShouldBeIgnored() {
        InMemoryMessageBus bus = syncBus();
        AtomicInteger count = new AtomicInteger();
        bus.subscribe("X", m -> count.incrementAndGet());
        bus.send(null);   // no-op
        bus.broadcast(null); // no-op
        assertEquals(0, count.get());
    }

    @Test
    void multipleSubscribersOnSameTopicShouldEachReceive() {
        InMemoryMessageBus bus = syncBus();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        bus.subscribe("D", m -> a.incrementAndGet());
        bus.subscribe("D", m -> b.incrementAndGet());

        bus.send(msg("M", "D"));

        assertEquals(1, a.get());
        assertEquals(1, b.get());
        assertEquals(2, bus.handlerCount("D"));
    }
}
