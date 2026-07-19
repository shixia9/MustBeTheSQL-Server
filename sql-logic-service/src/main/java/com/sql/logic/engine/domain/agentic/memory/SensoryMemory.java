package com.sql.logic.engine.domain.agentic.memory;

import com.sql.logic.engine.domain.agentic.core.MemoryFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Sensory memory — the perception buffer that sits at the front of the
 * three-tier memory cascade.
 * <p>
 * Buffer size defaults to 3 (small — designed to flush quickly).
 */
public class SensoryMemory {

    private final int bufferSize;
    private final double thresholdToShortTerm;
    private final List<MemoryFragment> fragments = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public SensoryMemory() {
        this(3, 0.1);
    }

    public SensoryMemory(int bufferSize, double thresholdToShortTerm) {
        this.bufferSize = bufferSize;
        this.thresholdToShortTerm = thresholdToShortTerm;
    }

    /**
     * Write a memory fragment to the sensory buffer.
     * @return list of overflow fragments that should be transferred to short-term
     */
    public List<MemoryFragment> write(MemoryFragment fragment) {
        lock.writeLock().lock();
        try {
            fragments.add(fragment);
            if (fragments.size() > bufferSize) {
                return handleOverflow();
            }
            return List.of();
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
        try {
            return fragments.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private List<MemoryFragment> handleOverflow() {
        // Transfer all fragments whose importance exceeds the threshold
        List<MemoryFragment> overflow = new ArrayList<>();
        List<MemoryFragment> kept = new ArrayList<>();

        for (MemoryFragment f : fragments) {
            if (f.importance() >= thresholdToShortTerm) {
                overflow.add(f);
            } else {
                kept.add(f);
            }
        }
        fragments.clear();
        fragments.addAll(kept);
        return overflow;
    }

    /** Create a structure clone (same config, empty buffer). */
    public SensoryMemory structureClone() {
        return new SensoryMemory(bufferSize, thresholdToShortTerm);
    }
}
