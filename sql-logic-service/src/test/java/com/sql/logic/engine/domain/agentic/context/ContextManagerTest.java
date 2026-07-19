package com.sql.logic.engine.domain.agentic.context;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 4-layer context compaction and ContextManager orchestration.
 */
class ContextManagerTest {

    private ContextBudgetConfig tightConfig;
    private ContextManager manager;

    @BeforeEach
    void setUp() {
        // Very tight budget to trigger compaction easily in tests
        tightConfig = new ContextBudgetConfig(
                500,    // max context tokens
                0.70,   // warning
                0.90,   // error
                0.95,   // critical
                50,     // reserved
                1,      // min keep rounds (keep only 1)
                3,      // max compact failures
                2,      // max observation age rounds
                100,    // truncated max chars
                50      // min keep tokens
        );
        manager = new ContextManager(tightConfig);
    }

    @Test
    void shouldNotCompactWhenUnderBudget() {
        List<AgentMessage> messages = List.of(
                AgentMessage.system("Short system prompt"),
                AgentMessage.user("Hello")
        );

        var result = manager.manageContext(messages, 0, null);
        assertEquals(messages.size(), result.size(), "Should not compact under-budget messages");
    }

    @Test
    void shouldApplyLayer2WhenOverWarning() {
        // Build a large message list that exceeds warning threshold
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system("System prompt " + "x".repeat(300)));

        // Add many rounds
        for (int i = 0; i < 10; i++) {
            messages.add(AgentMessage.user("User message " + i + " " + "y".repeat(50)));
            messages.add(AgentMessage.ai("AI response " + i + " " + "z".repeat(50)));
            messages.add(AgentMessage.builder()
                    .content("Tool output " + i + " " + "w".repeat(50))
                    .messageType(AgentMessage.MessageType.TOOL)
                    .build());
        }

        var result = manager.manageContext(messages, 5, "Task progress: step 1 done");

        // After Layer 2 compaction, should have fewer messages
        assertTrue(result.size() < messages.size(),
                "Should drop old rounds, expected < " + messages.size() + " but got " + result.size());
    }

    @Test
    void reactiveCompactShouldKeepOnlyLastRounds() {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system("System"));

        // Add 6 rounds (TOOL message marks end of round)
        for (int i = 0; i < 6; i++) {
            messages.add(AgentMessage.user("Q" + i));
            messages.add(AgentMessage.ai("A" + i));
            messages.add(AgentMessage.builder()
                    .content("Tool" + i)
                    .messageType(AgentMessage.MessageType.TOOL)
                    .build());
        }

        var result = manager.reactiveCompact(messages);
        assertTrue(result.size() < messages.size(),
                "Should keep only last 2 rounds, expected < " + messages.size() + " but got " + result.size());

        // System messages should be preserved
        long systemCount = result.stream()
                .filter(m -> m.messageType() == AgentMessage.MessageType.SYSTEM)
                .count();
        assertEquals(1, systemCount, "System messages should be preserved");
    }

    @Test
    void layer1ShouldTruncateOldObservations() {
        String longContent = "x".repeat(500); // Longer than max chars (100)

        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system("System"));
        messages.add(AgentMessage.user("Query"));
        messages.add(AgentMessage.ai("Response"));
        messages.add(AgentMessage.builder()
                .content(longContent)
                .messageType(AgentMessage.MessageType.TOOL)
                .build());

        ObservationMicroCompact layer1 = new ObservationMicroCompact();
        var result = layer1.compact(messages, 10, manager.getTracker());

        AgentMessage toolMsg = result.get(3);
        assertTrue(toolMsg.content().contains("[truncated]"),
                "Old observation should be truncated");
        assertTrue(toolMsg.content().length() < longContent.length(),
                "Truncated content should be shorter");
    }

    @Test
    void layer2ShouldDropOldRounds() {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system("System"));

        // 5 rounds
        for (int i = 0; i < 5; i++) {
            messages.add(AgentMessage.user("Q" + i + " " + "a".repeat(30)));
            messages.add(AgentMessage.ai("A" + i + " " + "b".repeat(30)));
            messages.add(AgentMessage.builder()
                    .content("T" + i + " " + "c".repeat(30))
                    .messageType(AgentMessage.MessageType.TOOL)
                    .build());
        }

        SessionMemoryCompact layer2 = new SessionMemoryCompact();
        var result = layer2.compact(messages, "progress", manager.getTracker());

        // Should keep only 1 round (minKeepRecentRounds = 1) + system
        assertTrue(result.size() < messages.size(),
                "Should drop old rounds");
    }

    @Test
    void layer4ReactiveCompactShouldKeepLast2() {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system("System"));

        for (int i = 0; i < 6; i++) {
            messages.add(AgentMessage.user("Q" + i));
            messages.add(AgentMessage.ai("A" + i));
            messages.add(AgentMessage.builder()
                    .content("T" + i)
                    .messageType(AgentMessage.MessageType.TOOL)
                    .build());
        }

        ReactiveCompact layer4 = new ReactiveCompact();
        var result = layer4.compact(messages, manager.getTracker());

        assertTrue(result.size() < messages.size());
    }

    @Test
    void contextManagerShouldRespectCircuitBreaker() {
        // Trip the circuit breaker
        for (int i = 0; i < 3; i++) {
            manager.getTracker().recordCompactFailure();
        }
        assertTrue(manager.getTracker().isCircuitBreakerTripped());

        // Even with large messages, should skip compaction
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system("x".repeat(400))); // Near budget limit

        var result = manager.manageContext(messages, 0, null);
        assertEquals(messages.size(), result.size(), "Should skip compaction when circuit breaker tripped");
    }
}
