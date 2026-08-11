package com.sql.logic.engine.domain.agentic.context;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for context budget tracking and state machine.
 */
class ContextBudgetTrackerTest {

    private ContextBudgetConfig config;
    private ContextBudgetTracker tracker;

    @BeforeEach
    void setUp() {
        config = new ContextBudgetConfig(
                1000,   // max context tokens (small for testing)
                0.70,   // warning at 70%
                0.90,   // error at 90%
                0.95,   // critical at 95%
                100,    // reserved tokens
                3,      // min keep rounds
                3,      // max compact failures
                5,      // max observation age rounds
                200,    // truncated observation max chars
                500     // min keep tokens
        );
        tracker = new ContextBudgetTracker(config);
    }

    @Test
    void shouldReturnNormalForLowTokenCount() {
        assertEquals(TokenState.NORMAL, tracker.getState(500)); // 500/900 = 55%
    }

    @Test
    void shouldReturnWarningAtThreshold() {
        assertEquals(TokenState.WARNING, tracker.getState(650)); // 650/900 = 72% > 70%
    }

    @Test
    void shouldReturnErrorAtThreshold() {
        assertEquals(TokenState.ERROR, tracker.getState(820)); // 820/900 = 91% > 90%
    }

    @Test
    void shouldReturnCriticalAtThreshold() {
        assertEquals(TokenState.CRITICAL, tracker.getState(860)); // 860/900 = 95.6% > 95%
    }

    @Test
    void shouldReturnOverflowAboveBudget() {
        assertEquals(TokenState.OVERFLOW, tracker.getState(1000)); // 1000/900 > 100%
    }

    @Test
    void shouldCountTokensInMessages() {
        List<AgentMessage> messages = List.of(
                AgentMessage.system("System prompt with some content"),
                AgentMessage.user("User query with more content here"),
                AgentMessage.ai("AI response with even more content to count")
        );

        int count = tracker.countMessages(messages);
        assertTrue(count > 0, "Should count tokens in messages");
    }

    @Test
    void shouldCountZeroForEmptyMessages() {
        assertEquals(0, tracker.countMessages(List.of()));
    }

    @Test
    void shouldTrackTokenHistory() {
        tracker.recordTokenCount(100);
        tracker.recordTokenCount(200);
        tracker.recordTokenCount(300);

        assertEquals(List.of(100, 200, 300), tracker.getTokenHistory());
    }

    @Test
    void circuitBreakerShouldTripAfterMaxFailures() {
        assertFalse(tracker.isCircuitBreakerTripped());

        tracker.recordCompactFailure();
        tracker.recordCompactFailure();
        tracker.recordCompactFailure(); // Third failure — should trip

        assertTrue(tracker.isCircuitBreakerTripped());
    }

    @Test
    void circuitBreakerShouldResetOnSuccess() {
        tracker.recordCompactFailure();
        tracker.recordCompactFailure();
        tracker.recordCompactSuccess();

        assertFalse(tracker.isCircuitBreakerTripped());
    }

    @Test
    void effectiveBudgetShouldExcludeReserved() {
        assertEquals(900, config.effectiveBudget()); // 1000 - 100 = 900
    }
}
