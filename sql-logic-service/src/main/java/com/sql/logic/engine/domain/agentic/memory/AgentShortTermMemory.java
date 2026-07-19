package com.sql.logic.engine.domain.agentic.memory;

import com.sql.logic.engine.domain.agentic.core.MemoryFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Enhanced short-term memory with embedding-based deduplication.
 * <p>
 * Keeps a bounded buffer of recent memory fragments. When the buffer overflows, the oldest fragments
 * are evicted and returned for transfer to long-term memory.
 * <p>
 * Deduplication: if a new fragment's observation is too similar to an existing
 * one (simple token overlap check), the existing fragment's importance is
 * updated rather than creating a duplicate.
 * <p>
 * Default buffer size is 5.
 */
public class AgentShortTermMemory {

    private final int bufferSize;
    private final double dedupSimilarityThreshold;
    private final List<MemoryFragment> fragments = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public AgentShortTermMemory() {
        this(5, 0.85);
    }

    public AgentShortTermMemory(int bufferSize, double dedupSimilarityThreshold) {
        this.bufferSize = bufferSize;
        this.dedupSimilarityThreshold = dedupSimilarityThreshold;
    }

    /**
     * Write a memory fragment to short-term memory.
     * @return overflow fragments that should be transferred to long-term memory, or null
     */
    public OverflowResult write(MemoryFragment fragment) {
        lock.writeLock().lock();
        try {
            // Dedup check
            for (int i = 0; i < fragments.size(); i++) {
                MemoryFragment existing = fragments.get(i);
                if (isDuplicate(existing, fragment)) {
                    // Update importance: take the max
                    double newImportance = Math.max(existing.importance(), fragment.importance());
                    fragments.set(i, existing.withImportance(newImportance));
                    return null; // No overflow, just updated
                }
            }

            fragments.add(fragment);
            return transferToLongTerm();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Write with explicit operation type. RETRIEVAL does NOT trigger overflow transfer. */
    public OverflowResult write(MemoryFragment fragment, WriteOp op) {
        lock.writeLock().lock();
        try {
            fragments.add(fragment);
            if (op == WriteOp.RETRIEVAL) {
                return null;
            }
            return transferToLongTerm();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<MemoryFragment> read() {
        lock.readLock().lock();
        try {
            return List.copyOf(fragments);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<MemoryFragment> clear() {
        lock.writeLock().lock();
        try {
            List<MemoryFragment> result = List.copyOf(fragments);
            fragments.clear();
            return result;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int size() {
        lock.readLock().lock();
        try { return fragments.size(); }
        finally { lock.readLock().unlock(); }
    }

    /** All fragments currently in short-term memory. */
    public List<MemoryFragment> getShortTermMemories() {
        lock.readLock().lock();
        try { return List.copyOf(fragments); }
        finally { lock.readLock().unlock(); }
    }

    private OverflowResult transferToLongTerm() {
        if (fragments.size() <= bufferSize) {
            return null;
        }
        int overflowCount = fragments.size() - bufferSize;
        List<MemoryFragment> overflow = new ArrayList<>();
        for (int i = 0; i < overflowCount; i++) {
            overflow.add(fragments.remove(0));
        }
        // Keep most recent in buffer
        return new OverflowResult(overflow, List.of());
    }

    private boolean isDuplicate(MemoryFragment a, MemoryFragment b) {
        if (a.observation() == null || b.observation() == null) return false;
        String obsA = a.observation().toLowerCase().trim();
        String obsB = b.observation().toLowerCase().trim();

        // Exact match
        if (obsA.equals(obsB)) return true;

        // Token overlap check
        Set<String> tokensA = tokenize(obsA);
        Set<String> tokensB = tokenize(obsB);
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false;

        Set<String> intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        double jaccard = (double) intersection.size() / union.size();
        return jaccard >= dedupSimilarityThreshold;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String word : text.split("[\\s,.;:!?()\\[\\]{}]+")) {
            if (word.length() > 2) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    public AgentShortTermMemory structureClone() {
        return new AgentShortTermMemory(bufferSize, dedupSimilarityThreshold);
    }

    // --- Inner types ---

    public enum WriteOp { ADD, RETRIEVAL }

    public record OverflowResult(List<MemoryFragment> discardedFragments, List<MemoryFragment> insights) {
        public boolean isEmpty() { return discardedFragments.isEmpty(); }
    }
}
