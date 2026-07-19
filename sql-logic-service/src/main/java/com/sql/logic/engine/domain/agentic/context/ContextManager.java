package com.sql.logic.engine.domain.agentic.context;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Orchestrates progressive multi-layer context compaction.
 * <p>
 * Layers are applied in order of increasing aggressiveness:
 * <pre>
 *   Layer 1 (WARNING): ObservationMicroCompact  — truncate old observations
 *   Layer 2 (WARNING): SessionMemoryCompact      — drop old rounds
 *   Layer 3 (ERROR):   FullContextCompression    — LLM summary
 *   Layer 4 (reactive): ReactiveCompact           — emergency last-resort trim
 * </pre>
 */
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final ContextBudgetTracker tracker;
    private final LLMStrategy llmStrategy;

    private final ObservationMicroCompact layer1 = new ObservationMicroCompact();
    private final SessionMemoryCompact layer2 = new SessionMemoryCompact();
    private final FullContextCompression layer3 = new FullContextCompression();
    private final ReactiveCompact layer4 = new ReactiveCompact();

    public ContextManager(ContextBudgetConfig config) {
        this(config, null);
    }

    public ContextManager(ContextBudgetConfig config, LLMStrategy llmStrategy) {
        this.tracker = new ContextBudgetTracker(config);
        this.llmStrategy = llmStrategy;
    }

    public ContextManager(ContextBudgetConfig config, LLMStrategy llmStrategy, String modelName) {
        this.tracker = new ContextBudgetTracker(config, modelName);
        this.llmStrategy = llmStrategy;
    }

    /**
     * Apply progressive compaction based on current token budget state.
     *
     * @param messages      the full list of agent messages
     * @param currentRound  current retry/round counter
     * @param taskProgress  task progress summary string (for Layer 2 implicit summary)
     * @return possibly compacted list of messages
     */
    public List<AgentMessage> manageContext(List<AgentMessage> messages, int currentRound,
                                            String taskProgress) {
        int tokenCount = tracker.countMessages(messages);
        tracker.recordTokenCount(tokenCount);
        TokenState state = tracker.getState(tokenCount);

        log.debug("Context status: tokens={}, budget={}, state={}",
                tokenCount, tracker.getConfig().effectiveBudget(), state);

        if (state == TokenState.NORMAL) {
            return messages;
        }

        if (tracker.isCircuitBreakerTripped()) {
            log.warn("Context compaction circuit breaker tripped — skipping compaction");
            return messages;
        }

        log.info("Context management triggered: state={}, tokens={}, budget={}",
                state, tokenCount, tracker.getConfig().effectiveBudget());

        // Layer 1: truncate old observations
        if (state.isGte(TokenState.WARNING)) {
            messages = layer1.compact(messages, currentRound, tracker);
            tokenCount = tracker.countMessages(messages);
            state = tracker.getState(tokenCount);
        }

        // Layer 2: drop old rounds (no LLM needed)
        if (state.isGte(TokenState.WARNING)) {
            messages = layer2.compact(messages, taskProgress, tracker);
            tokenCount = tracker.countMessages(messages);
            state = tracker.getState(tokenCount);
        }

        // Layer 3: LLM-based summarization
        if (state.isGte(TokenState.ERROR) && llmStrategy != null) {
            try {
                messages = layer3.compact(messages, llmStrategy, tracker);
                tracker.recordCompactSuccess();
                tokenCount = tracker.countMessages(messages);
                state = tracker.getState(tokenCount);
            } catch (Exception e) {
                tracker.recordCompactFailure();
                log.warn("Layer 3 compaction failed: {}", e.getMessage());
            }
        }

        return messages;
    }

    /**
     * Emergency compaction triggered by context_too_long errors from the LLM.
     */
    public List<AgentMessage> reactiveCompact(List<AgentMessage> messages) {
        log.warn("Reactive compaction triggered (Layer 4)");
        return layer4.compact(messages, tracker);
    }

    // --- Accessors ---

    public ContextBudgetTracker getTracker() { return tracker; }
    public ContextBudgetConfig getConfig() { return tracker.getConfig(); }
}
