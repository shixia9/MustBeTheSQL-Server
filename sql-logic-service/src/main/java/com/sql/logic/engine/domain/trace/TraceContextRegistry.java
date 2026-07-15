package com.sql.logic.engine.domain.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-thread {@link TraceContext} lookup for nodes that only have
 * {@link com.alibaba.cloud.ai.graph.OverAllState} (which is serialised into
 * MemorySaver checkpoints and therefore cannot carry a non-serialisable
 * TraceContext instance).
 * <p>
 * The runner registers the TraceContext here keyed by threadId at run start,
 * and each LLM-calling node reads {@code state.value(THREAD_ID)} to fetch its
 * trace carrier. Entries are removed on stream completion (doFinally) by the
 * controller; a TTL sweep defends against leaked entries on abnormal exits.
 */
@Component
public class TraceContextRegistry {

    private static final Logger log = LoggerFactory.getLogger(TraceContextRegistry.class);

    /** Lifetime of a registry entry (30 minutes — matches HitlSessionRegistry). */
    static final long TTL_MS = 30 * 60 * 1000L;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void register(String threadId, TraceContext traceContext) {
        if (threadId == null || traceContext == null) return;
        entries.put(threadId, new Entry(traceContext, System.currentTimeMillis()));
    }

    public Optional<TraceContext> get(String threadId) {
        if (threadId == null) return Optional.empty();
        Entry e = entries.get(threadId);
        if (e == null) return Optional.empty();
        if (isExpired(e)) {
            entries.remove(threadId);
            return Optional.empty();
        }
        return Optional.of(e.traceContext);
    }

    public void remove(String threadId) {
        if (threadId != null) entries.remove(threadId);
    }

    private boolean isExpired(Entry e) {
        return e == null || (System.currentTimeMillis() - e.createdAt) > TTL_MS;
    }

    /** Periodic cleanup hook — called opportunistically by register()/get(). */
    public void pruneExpired() {
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            if (isExpired(it.next().getValue())) it.remove();
        }
    }

    private static class Entry {
        final TraceContext traceContext;
        final long createdAt;
        Entry(TraceContext traceContext, long createdAt) {
            this.traceContext = traceContext;
            this.createdAt = createdAt;
        }
    }
}