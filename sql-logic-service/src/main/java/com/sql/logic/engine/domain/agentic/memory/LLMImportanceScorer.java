package com.sql.logic.engine.domain.agentic.memory;

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
 * The scoring prompt asks the LLM to rate importance on a 1-10 integer scale,
 * which is then normalized to 0.0-1.0 and weighted by {@code importanceWeight}.
 * <p>
 * Scoring is non-blocking: calls are submitted to a virtual thread executor
 * and results are returned via CompletableFuture.
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

    private final LLMStrategy llmStrategy;
    private final double importanceWeight;

    public LLMImportanceScorer(LLMStrategy llmStrategy) {
        this(llmStrategy, 0.15);
    }

    public LLMImportanceScorer(LLMStrategy llmStrategy, double importanceWeight) {
        this.llmStrategy = llmStrategy;
        this.importanceWeight = importanceWeight;
    }

    /**
     * Score a single memory fragment asynchronously.
     */
    public CompletableFuture<Double> scoreAsync(MemoryFragment fragment) {
        return CompletableFuture.supplyAsync(() -> score(fragment), executor);
    }

    /**
     * Score a single memory fragment synchronously.
     */
    public double score(MemoryFragment fragment) {
        if (llmStrategy == null || fragment == null) return DEFAULT_IMPORTANCE * importanceWeight;
        try {
            String prompt = String.format(SCORING_PROMPT, truncate(fragment.observation(), 500));
            String response = llmStrategy.generateSql(prompt, null);
            int rawScore = parseScore(response);
            return (rawScore / 10.0) * importanceWeight;
        } catch (Exception e) {
            log.debug("Importance scoring failed: {}", e.getMessage());
            return DEFAULT_IMPORTANCE * importanceWeight;
        }
    }

    /**
     * Score a batch of memory fragments. Each returned score is normalized to [0,1].
     */
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
        // Extract first integer from the response
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
