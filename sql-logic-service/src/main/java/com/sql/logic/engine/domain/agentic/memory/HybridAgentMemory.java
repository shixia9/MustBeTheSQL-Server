package com.sql.logic.engine.domain.agentic.memory;

import com.sql.logic.engine.domain.agentic.core.AgentMemory;
import com.sql.logic.engine.domain.agentic.core.MemoryFragment;
import com.sql.logic.engine.domain.memory.MemoryDomainService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Hybrid memory — orchestrates the three-tier memory cascade.
 * <p>
 * Coordinates SensoryMemory, AgentShortTermMemory, and AgentLongTermMemory through a write cascade:
 * <pre>
 *   write(fragment)
 *     → SensoryMemory.write()          [perception buffer]
 *       → overflow → ShortTerm.write() [recent context, dedup]
 *         → overflow → InsightExtractor → LongTerm.writeBatch()  [persistent]
 * </pre>
 * <p>
 * On read(), both short-term and long-term memories are queried and
 * merged, with short-term entries having priority.
 */
public class HybridAgentMemory implements AgentMemory {

    private final SensoryMemory sensoryMemory;
    private final AgentShortTermMemory shortTermMemory;
    private final AgentLongTermMemory longTermMemory;
    private final LLMImportanceScorer importanceScorer;
    private final LLMInsightExtractor insightExtractor;
    private final ExecutorService asyncExecutor;

    // Task progress tracker (independent of message serialization)
    private final List<TaskProgressEntry> taskProgress = new ArrayList<>();

    public HybridAgentMemory(
            SensoryMemory sensoryMemory,
            AgentShortTermMemory shortTermMemory,
            AgentLongTermMemory longTermMemory,
            LLMImportanceScorer importanceScorer,
            LLMInsightExtractor insightExtractor) {
        this.sensoryMemory = sensoryMemory;
        this.shortTermMemory = shortTermMemory;
        this.longTermMemory = longTermMemory;
        this.importanceScorer = importanceScorer;
        this.insightExtractor = insightExtractor;
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Convenience constructor with defaults and LLM.
     */
    public HybridAgentMemory(
            MemoryDomainService memoryDomainService,
            LLMImportanceScorer importanceScorer,
            LLMInsightExtractor insightExtractor) {
        this(new SensoryMemory(3, 0.1),
             new AgentShortTermMemory(5, 0.85),
             new AgentLongTermMemory(memoryDomainService),
             importanceScorer,
             insightExtractor);
    }

    /**
     * Convenience constructor without LLM (uses default scoring).
     */
    public HybridAgentMemory(MemoryDomainService memoryDomainService) {
        this(new SensoryMemory(3, 0.1),
             new AgentShortTermMemory(5, 0.85),
             new AgentLongTermMemory(memoryDomainService),
             null,
             null);
    }

    public void setIdentity(Long userId, Long agentId) {
        longTermMemory.setIdentity(userId, agentId);
    }

    // ========================================================================
    //  Write cascade
    // ========================================================================

    @Override
    public void write(MemoryFragment fragment) {
        if (fragment == null) return;

        // Score importance asynchronously if scorer is available
        if (importanceScorer != null) {
            asyncExecutor.submit(() -> {
                double score = importanceScorer.score(fragment);
                MemoryFragment scored = fragment.withImportance(score);
                doWrite(scored);
            });
        } else {
            doWrite(fragment);
        }
    }

    private void doWrite(MemoryFragment fragment) {
        // Step 1: Sensory buffer
        List<MemoryFragment> sensoryOverflow = sensoryMemory.write(fragment);

        // Step 2: Transfer overflow to short-term
        for (MemoryFragment f : sensoryOverflow) {
            var result = shortTermMemory.write(f);
            if (result != null && !result.isEmpty()) {
                // Step 3: Transfer short-term overflow to long-term
                List<MemoryFragment> toLongTerm = new ArrayList<>(result.discardedFragments());

                // Extract insights from evicted fragments (async, non-blocking)
                if (insightExtractor != null && !toLongTerm.isEmpty()) {
                    asyncExecutor.submit(() -> {
                        List<MemoryFragment> insights = insightExtractor.extract(toLongTerm);
                        if (!insights.isEmpty()) {
                            longTermMemory.writeBatch(insights);
                        }
                    });
                }

                longTermMemory.writeBatch(toLongTerm);
            }
        }
    }

    @Override
    public void writeBatch(List<MemoryFragment> fragments) {
        if (fragments == null) return;
        for (MemoryFragment f : fragments) {
            write(f);
        }
    }

    // ========================================================================
    //  Read (merged short-term + long-term)
    // ========================================================================

    @Override
    public String read(String query) {
        StringBuilder sb = new StringBuilder();

        // Sensory memory (most recent perceptions)
        List<MemoryFragment> sensoryFragments = sensoryMemory.read();
        if (!sensoryFragments.isEmpty()) {
            sb.append("### Perceptual Context\n");
            for (MemoryFragment f : sensoryFragments) {
                sb.append("- ").append(f.observation()).append("\n");
            }
            sb.append("\n");
        }

        // Short-term recall
        List<MemoryFragment> shortTermFragments = shortTermMemory.getShortTermMemories();
        if (!shortTermFragments.isEmpty()) {
            sb.append("### Recent Context\n");
            for (MemoryFragment f : shortTermFragments) {
                sb.append("- ").append(f.observation()).append("\n");
            }
            sb.append("\n");
        }

        // Long-term recall via vector search
        List<MemoryFragment> longTermFragments = longTermMemory.fetchMemories(query);
        if (!longTermFragments.isEmpty()) {
            sb.append("### Relevant Historical Knowledge\n");
            for (MemoryFragment f : longTermFragments) {
                sb.append("- ").append(f.observation())
                        .append(" [importance=").append(String.format("%.2f", f.importance())).append("]\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    @Override
    public List<MemoryFragment> getRecentFragments(int count) {
        List<MemoryFragment> all = new ArrayList<>();
        all.addAll(sensoryMemory.read());
        all.addAll(shortTermMemory.getShortTermMemories());
        return all.stream().limit(count).collect(Collectors.toList());
    }

    @Override
    public List<MemoryFragment> clear() {
        List<MemoryFragment> all = new ArrayList<>();
        all.addAll(sensoryMemory.clear());
        all.addAll(shortTermMemory.clear());
        taskProgress.clear();
        return all;
    }

    @Override
    public int totalFragmentCount() {
        return sensoryMemory.size() + shortTermMemory.size();
    }

    // ========================================================================
    //  Task progress tracking
    // ========================================================================

    @Override
    public void recordTaskProgress(TaskProgressEntry entry) {
        taskProgress.add(entry);
    }

    @Override
    public String getTaskProgressSummary() {
        if (taskProgress.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("## Task Progress (do NOT repeat completed steps)\n");
        for (TaskProgressEntry entry : taskProgress) {
            sb.append(entry.status() == TaskStatus.DONE ? "[DONE]" : "[FAILED]")
                    .append(" Step ").append(entry.step())
                    .append(": ").append(entry.action())
                    .append(" | Phase: ").append(entry.phase());
            if (entry.snapshot() != null && !entry.snapshot().isBlank()) {
                sb.append(" | Snapshot: ").append(truncate(entry.snapshot(), 100));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public void flushToLongTerm() {
        // Flush all sensory and short-term memory to long-term storage
        List<MemoryFragment> all = new ArrayList<>();
        all.addAll(sensoryMemory.clear());
        all.addAll(shortTermMemory.clear());
        if (!all.isEmpty()) {
            longTermMemory.writeBatch(all);
        }
    }

    // --- Accessors for testing ---

    public SensoryMemory getSensoryMemory() { return sensoryMemory; }
    public AgentShortTermMemory getShortTermMemory() { return shortTermMemory; }
    public AgentLongTermMemory getLongTermMemory() { return longTermMemory; }
    public int taskProgressSize() { return taskProgress.size(); }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
