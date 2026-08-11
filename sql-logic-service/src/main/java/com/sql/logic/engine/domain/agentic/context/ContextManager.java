package com.sql.logic.engine.domain.agentic.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.core.AgentEventSinkRegistry;
import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p>When an {@link AgentEventSinkRegistry} and a non-null {@code threadId} are
 * supplied, each compaction layer emits a {@code CONTEXT_COMPACT} SSE event so the
 * frontend can render a live compaction animation panel. Emission is best-effort
 * and never blocks the compaction pipeline.
 */
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private static final ObjectMapper SSE_MAPPER = new ObjectMapper();

    /** Display names for the 4 compaction layers (mirrors the frontend constant). */
    private static final Map<String, String> LAYER_NAMES = Map.of(
            "L1", "截断观察",
            "L2", "丢弃旧轮",
            "L3", "LLM 摘要",
            "L4", "紧急压缩"
    );

    private final ContextBudgetTracker tracker;
    private final LLMStrategy llmStrategy;
    private final LlmClientManager llmClientManager;
    /** Optional — when set together with a threadId, compaction events are streamed. */
    private final AgentEventSinkRegistry eventSinkRegistry;

    private final ObservationMicroCompact layer1 = new ObservationMicroCompact();
    private final SessionMemoryCompact layer2 = new SessionMemoryCompact();
    private final FullContextCompression layer3 = new FullContextCompression();
    private final ReactiveCompact layer4 = new ReactiveCompact();

    public ContextManager(ContextBudgetConfig config) {
        this.tracker = new ContextBudgetTracker(config);
        this.llmStrategy = null;
        this.llmClientManager = null;
        this.eventSinkRegistry = null;
    }

    public ContextManager(ContextBudgetConfig config, LLMStrategy llmStrategy) {
        this.tracker = new ContextBudgetTracker(config);
        this.llmStrategy = llmStrategy;
        this.llmClientManager = null;
        this.eventSinkRegistry = null;
    }

    public ContextManager(ContextBudgetConfig config, LLMStrategy llmStrategy, String modelName) {
        this.tracker = new ContextBudgetTracker(config, modelName != null ? modelName : "gpt-4");
        this.llmStrategy = llmStrategy;
        this.llmClientManager = null;
        this.eventSinkRegistry = null;
    }

    public ContextManager(ContextBudgetConfig config, LlmClientManager llmClientManager) {
        this.tracker = new ContextBudgetTracker(config);
        this.llmStrategy = null;
        this.llmClientManager = llmClientManager;
        this.eventSinkRegistry = null;
    }

    /**
     * Full constructor with lazy LLM client + optional SSE event sink for live
     * compaction visualization.
     */
    public ContextManager(ContextBudgetConfig config, LlmClientManager llmClientManager,
                          AgentEventSinkRegistry eventSinkRegistry) {
        this.tracker = new ContextBudgetTracker(config);
        this.llmStrategy = null;
        this.llmClientManager = llmClientManager;
        this.eventSinkRegistry = eventSinkRegistry;
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
        return manageContext(messages, currentRound, taskProgress, null);
    }

    /**
     * Apply progressive compaction based on current token budget state.
     *
     * @param messages      the full list of agent messages
     * @param currentRound  current retry/round counter
     * @param taskProgress  task progress summary string (for Layer 2 implicit summary)
     * @param threadId      optional graph thread id — when non-null and an event
     *                      sink registry is bound, each applied layer streams a
     *                      {@code CONTEXT_COMPACT} SSE event for the live UI panel
     * @return possibly compacted list of messages
     */
    public List<AgentMessage> manageContext(List<AgentMessage> messages, int currentRound,
                                            String taskProgress, String threadId) {
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
            int before = tokenCount;
            int sizeBefore = messages.size();
            messages = layer1.compact(messages, currentRound, tracker);
            tokenCount = tracker.countMessages(messages);
            state = tracker.getState(tokenCount);
            emitCompaction(threadId, "L1", before, tokenCount, sizeBefore - messages.size(), messages);
        }

        // Layer 2: drop old rounds (no LLM needed)
        if (state.isGte(TokenState.WARNING)) {
            int before = tokenCount;
            int sizeBefore = messages.size();
            messages = layer2.compact(messages, taskProgress, tracker);
            tokenCount = tracker.countMessages(messages);
            state = tracker.getState(tokenCount);
            emitCompaction(threadId, "L2", before, tokenCount, sizeBefore - messages.size(), messages);
        }

        // Layer 3: LLM-based summarization
        LLMStrategy effectiveStrategy = llmStrategy != null ? llmStrategy
                : (llmClientManager != null ? llmClientManager.getClient(0L) : null);
        if (state.isGte(TokenState.ERROR) && effectiveStrategy != null) {
            int before = tokenCount;
            int sizeBefore = messages.size();
            try {
                messages = layer3.compact(messages, effectiveStrategy, tracker);
                tracker.recordCompactSuccess();
                tokenCount = tracker.countMessages(messages);
                state = tracker.getState(tokenCount);
                emitCompaction(threadId, "L3", before, tokenCount, sizeBefore - messages.size(), messages);
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
        return reactiveCompact(messages, null);
    }

    /**
     * Emergency compaction with optional thread id for SSE streaming (Layer 4).
     */
    public List<AgentMessage> reactiveCompact(List<AgentMessage> messages, String threadId) {
        log.warn("Reactive compaction triggered (Layer 4)");
        int before = tracker.countMessages(messages);
        int sizeBefore = messages.size();
        List<AgentMessage> compacted = layer4.compact(messages, tracker);
        int after = tracker.countMessages(compacted);
        emitCompaction(threadId, "L4", before, after, sizeBefore - compacted.size(), compacted);
        return compacted;
    }

    // --- Accessors ---

    public ContextBudgetTracker getTracker() { return tracker; }
    public ContextBudgetConfig getConfig() { return tracker.getConfig(); }

    // --- SSE compaction event emission ---

    /**
     * Best-effort emission of a {@code CONTEXT_COMPACT} SSE event for the live
     * compaction panel. Silently no-ops when no registry is bound, no threadId is
     * supplied, the sink is no longer registered (e.g. stream already completed),
     * or the layer produced no change. Never throws — emission must never block or
     * break the compaction pipeline.
     *
     * <p>Event shape (consumed by {@code ChatPage.tsx} and {@code CompactionPanel}):
     * <pre>
     * {"nodeName":"CONTEXT","outputType":"CONTEXT_COMPACT","messageType":"STATUS",
     *  "data":{"layer":"L2","layerName":"丢弃旧轮","tokensBefore":N,"tokensAfter":M,
     *          "dropped":K,"preview":"…"}}
     * </pre>
     */
    private void emitCompaction(String threadId, String layer, int tokensBefore, int tokensAfter,
                                int dropped, List<AgentMessage> retained) {
        if (eventSinkRegistry == null || threadId == null) return;
        if (tokensAfter == tokensBefore && dropped <= 0) return;
        Sinks.Many<String> sink = eventSinkRegistry.get(threadId);
        if (sink == null) return;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("layer", layer);
            data.put("layerName", LAYER_NAMES.getOrDefault(layer, layer));
            data.put("tokensBefore", tokensBefore);
            data.put("tokensAfter", tokensAfter);
            data.put("dropped", Math.max(0, dropped));
            data.put("preview", previewRetained(retained));

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("nodeName", "CONTEXT");
            event.put("outputType", "CONTEXT_COMPACT");
            event.put("messageType", "STATUS");
            event.put("sequenceNo", 0);
            event.put("data", data);
            sink.tryEmitNext(SSE_MAPPER.writeValueAsString(event));
        } catch (Exception e) {
            log.debug("[ContextManager] compaction SSE emit skipped: {}", e.getMessage());
        }
    }

    /**
     * Build a short preview string from the first non-blank retained message —
     * gives the user a hint of what context survived compaction. Truncated to 120
     * chars to keep the SSE payload small.
     */
    private String previewRetained(List<AgentMessage> retained) {
        if (retained == null || retained.isEmpty()) return null;
        for (AgentMessage m : retained) {
            String c = m.content();
            if (c == null || c.isBlank()) continue;
            return c.length() > 120 ? c.substring(0, 120) + "…" : c;
        }
        return null;
    }
}
