package com.sql.logic.engine.domain.agentic.memory;

import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agentic.core.MemoryFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * LLM-based insight extractor for memory fragments.
 * <p>
 * Extracts high-level insights from memory fragments evicted from short-term
 * memory. These insights are persisted to long-term memory as condensed entries.
 * <p>
 * Resolves the LLM strategy lazily via {@link LlmClientManager} (system default).
 */
public class LLMInsightExtractor {

    private static final Logger log = LoggerFactory.getLogger(LLMInsightExtractor.class);
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private static final String EXTRACTION_PROMPT = """
            Extract ONE high-level insight from these recent observations. \
            The insight should be a concise, reusable statement about the user's \
            patterns, preferences, or the data. Do NOT repeat raw data — derive meaning.

            Observations:
            %s

            Reply with ONLY the insight (one sentence, max 100 characters).""";

    private final LlmClientManager llmClientManager;

    public LLMInsightExtractor(LlmClientManager llmClientManager) {
        this.llmClientManager = llmClientManager;
    }

    private LLMStrategy resolveStrategy() {
        if (llmClientManager == null) return null;
        return llmClientManager.getClient(0L);
    }

    public CompletableFuture<List<MemoryFragment>> extractAsync(List<MemoryFragment> fragments) {
        return CompletableFuture.supplyAsync(() -> extract(fragments), executor);
    }

    public List<MemoryFragment> extract(List<MemoryFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) return List.of();
        LLMStrategy strategy = resolveStrategy();
        if (strategy == null) return List.of();
        try {
            StringBuilder observations = new StringBuilder();
            for (int i = 0; i < fragments.size(); i++) {
                observations.append(i + 1).append(". ")
                        .append(truncate(fragments.get(i).observation(), 300))
                        .append("\n");
            }
            String prompt = String.format(EXTRACTION_PROMPT, observations.toString());
            String response = strategy.chat(prompt);

            if (response != null && !response.isBlank()) {
                String insight = response.trim();
                if (insight.length() > 200) insight = insight.substring(0, 200);
                double avgImportance = fragments.stream()
                        .mapToDouble(MemoryFragment::importance)
                        .average()
                        .orElse(0.6);
                return List.of(MemoryFragment.of(insight, "INSIGHT", avgImportance).withInsight(true));
            }
        } catch (Exception e) {
            log.debug("Insight extraction failed: {}", e.getMessage());
        }
        return List.of();
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
