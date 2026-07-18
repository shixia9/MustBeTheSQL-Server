package com.sql.logic.engine.domain.agentic.memory;

import com.sql.logic.engine.domain.agentic.core.AgentMemory;
import com.sql.logic.engine.domain.agentic.core.MemoryFragment;
import com.sql.logic.engine.domain.memory.MemoryDomainService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

/**
 * In-memory Agent memory implementation.
 * <p>
 * Uses a bounded {@code Deque<MemoryFragment>} (max 5 entries) for short-term
 * recall and delegates long-term reads to the existing {@link MemoryDomainService}.
 * Long-term writes are a no-op in Phase 1 (full hybrid memory comes in Phase 3).
 * <p>
 * The task progress tracker is independent of the short-term buffer — it never
 * loses completed steps, even when the short-term buffer overflows.
 */
public class SimpleAgentMemory implements AgentMemory {

    private static final int MAX_SHORT_TERM = 5;

    private final Deque<MemoryFragment> shortTerm = new ArrayDeque<>(MAX_SHORT_TERM);
    private final List<TaskProgressEntry> taskProgress = new ArrayList<>();
    private final MemoryDomainService longTermService;
    private final Long userId;
    private final Long agentId;

    public SimpleAgentMemory(MemoryDomainService longTermService, Long userId, Long agentId) {
        this.longTermService = longTermService;
        this.userId = userId;
        this.agentId = agentId;
    }

    public SimpleAgentMemory() {
        this.longTermService = null;
        this.userId = null;
        this.agentId = null;
    }

    @Override
    public String read(String query) {
        StringBuilder sb = new StringBuilder();

        // Short-term recall (recent observations)
        if (!shortTerm.isEmpty()) {
            sb.append("### Recent Context\n");
            for (MemoryFragment frag : shortTerm) {
                sb.append("- ").append(frag.observation()).append("\n");
            }
            sb.append("\n");
        }

        // Long-term recall (via existing pgvector infrastructure)
        if (longTermService != null && userId != null && query != null && !query.isBlank()) {
            try {
                List<java.util.Map<String, Object>> results =
                        longTermService.searchRelevant(userId, agentId, query, 3);
                if (!results.isEmpty()) {
                    sb.append("### Relevant Historical Knowledge\n");
                    for (java.util.Map<String, Object> entry : results) {
                        Object content = entry.get("content");
                        if (content != null) {
                            sb.append("- ").append(content).append("\n");
                        }
                    }
                }
            } catch (Exception ignored) {
                // Long-term recall is best-effort; failures must not block the pipeline
            }
        }

        return sb.toString();
    }

    @Override
    public void write(MemoryFragment fragment) {
        if (fragment == null) return;
        shortTerm.addFirst(fragment);
        // Evict oldest when over capacity
        while (shortTerm.size() > MAX_SHORT_TERM) {
            shortTerm.pollLast();
        }
    }

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
                    .append(" | Phase: ").append(entry.phase())
                    .append("\n");
        }
        return sb.toString();
    }

    @Override
    public List<MemoryFragment> getRecentFragments(int count) {
        return shortTerm.stream()
                .limit(count)
                .collect(Collectors.toList());
    }

    public int shortTermSize() {
        return shortTerm.size();
    }

    public int taskProgressSize() {
        return taskProgress.size();
    }
}
