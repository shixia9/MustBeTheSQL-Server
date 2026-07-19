package com.sql.logic.engine.domain.agentic.memory;

import com.sql.logic.engine.domain.agentic.core.MemoryFragment;
import com.sql.logic.engine.domain.memory.CandidateMemory;
import com.sql.logic.engine.domain.memory.MemoryDomainService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Long-term memory backed by pgvector (via {@link MemoryDomainService}).
 * <p>
 * Persists memory fragments to a vector store for future similarity-based retrieval.
 * Write operations are delegated to the existing 
 * {@code MemoryDomainService.saveMemories()} infrastructure.
 */
public class AgentLongTermMemory {

    private final MemoryDomainService memoryDomainService;
    private final ExecutorService executor;
    private Long userId;
    private Long agentId;

    public AgentLongTermMemory(MemoryDomainService memoryDomainService) {
        this.memoryDomainService = memoryDomainService;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public AgentLongTermMemory(MemoryDomainService memoryDomainService, Long userId, Long agentId) {
        this(memoryDomainService);
        this.userId = userId;
        this.agentId = agentId;
    }

    public void setIdentity(Long userId, Long agentId) {
        this.userId = userId;
        this.agentId = agentId;
    }

    /**
     * Retrieve memories similar to the given observation.
     */
    public List<MemoryFragment> fetchMemories(String observation) {
        if (memoryDomainService == null || userId == null || observation == null || observation.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> results = memoryDomainService.searchRelevant(userId, agentId, observation, 5);
            List<MemoryFragment> fragments = new ArrayList<>();
            for (Map<String, Object> entry : results) {
                Object content = entry.get("content");
                Object importance = entry.get("importance");
                if (content != null) {
                    fragments.add(new MemoryFragment(
                            content.toString(),
                            importance instanceof Number n ? n.doubleValue() : 0.5,
                            "LONG_TERM",
                            false,
                            null,
                            null,
                            entry.get("id") instanceof Number n ? n.longValue() : null
                    ));
                }
            }
            return fragments;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Persist a batch of fragments to long-term storage asynchronously.
     */
    public void writeBatch(List<MemoryFragment> fragments) {
        if (fragments == null || fragments.isEmpty() || memoryDomainService == null || userId == null) {
            return;
        }
        executor.submit(() -> {
            try {
                List<CandidateMemory> candidates = new ArrayList<>();
                for (MemoryFragment f : fragments) {
                    CandidateMemory cm = new CandidateMemory();
                    cm.setText(f.observation());
                    cm.setType(f.memoryType() != null ? f.memoryType() : "TASK");
                    cm.setImportance(f.importance());
                    candidates.add(cm);
                }
                memoryDomainService.saveMemories(userId, null, agentId, null, candidates);
            } catch (Exception ignored) {
                // Best-effort persistence
            }
        });
    }

    public AgentLongTermMemory structureClone() {
        return new AgentLongTermMemory(memoryDomainService, userId, agentId);
    }
}
