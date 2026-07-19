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
 * LLM-based memory importance scorer.
 * <p>
 * Rates each memory fragment on a 0.0-1.0 scale by asking the LLM to evaluate
 * its significance on a 1-10 integer scale, normalized and weighted.
 * <p>
 * Resolves the LLM strategy lazily via {@link LlmClientManager} (system default)
 * so it works without per-request wiring. Falls back to default importance when
 * no LLM is available.
 */
public class LLMImportanceScorer {

    private static final Logger log = LoggerFactory.getLogger(LLMImportanceScorer.class);
    private static final double DEFAULT_IMPORTANCE = 0.5;
    private static final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private static final String SCORING_PROMPT = """
            On a scale of 1 to 10, rate the importance of this memory fragment for \
            future data analysis tasks. Consider:
            - Does it reveal a user preference or pattern?
            - Does it contain a reusable insight?
            - Would losing this information hurt future task quality?

            Memory fragment:
            "%s"

            Reply with ONLY the integer score (1-10).""";

    private final LlmClientManager llmClientManager;
    private final double importanceWeight;

    public LLMImportanceScorer(LlmClientManager llmClientManager) {
        this(llmClientManager, 0.15);
    }

    public LLMImportanceScorer(LlmClientManager llmClientManager, double importanceWeight) {
        this.llmClientManager = llmClientManager;
        this.importanceWeight = importanceWeight;
    }

    /** Resolve the system-default LLM strategy lazily. */
    private LLMStrategy resolveStrategy() {
        if (llmClientManager == null) return null;
        return llmClientManager.getClient(0L);
    }

    public CompletableFuture<Double> scoreAsync(MemoryFragment fragment) {
        return CompletableFuture.supplyAsync(() -> score(fragment), executor);
    }

    public double score(MemoryFragment fragment) {
        if (fragment == null) return DEFAULT_IMPORTANCE * importanceWeight;
        LLMStrategy strategy = resolveStrategy();
        if (strategy == null) return DEFAULT_IMPORTANCE * importanceWeight;
        try {
            String prompt = String.format(SCORING_PROMPT, truncate(fragment.observation(), 500));
            String response = strategy.chat(prompt);
            int rawScore = parseScore(response);
            return (rawScore / 10.0) * importanceWeight;
        } catch (Exception e) {
            log.debug("Importance scoring failed: {}", e.getMessage());
            return DEFAULT_IMPORTANCE * importanceWeight;
        }
    }

    public double[] scoreBatch(List<MemoryFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) return new double[0];
        double[] scores = new double[fragments.size()];
        for (int i = 0; i < fragments.size(); i++) {
            scores[i] = score(fragments.get(i));
        }
        return scores;
    }

    private int parseScore(String llmOutput) {
        if (llmOutput == null) return 5;
        String cleaned = llmOutput.replaceAll("[^0-9]", " ").trim();
        if (cleaned.isEmpty()) return 5;
        try {
            int score = Integer.parseInt(cleaned.split("\\s+")[0]);
            return Math.clamp(score, 1, 10);
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
