package com.sql.logic.engine.domain.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of pending HITL (human-in-the-loop) sessions, keyed by threadId.
 * <p>
 * When a first-run graph execution pauses before the {@code HITL} interrupt node, the
 * runner stores the {@link AgentRunContext} here so the subsequent {@code /confirm}
 * HTTP call can resume the same checkpoint. Sessions expire after
 * {@link #SESSION_TTL_MS} to bound memory usage.
 * <p>
 * Single-process, in-memory by design — matches the requirement that requests resume
 * on the same backend instance. Swap for a shared store if horizontal scaling is needed.
 */
@Component
public class HitlSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(HitlSessionRegistry.class);

    /** Lifetime of a pending confirmation session (30 minutes). */
    static final long SESSION_TTL_MS = 30 * 60 * 1000L;

    private final Map<String, AgentRunContext> sessions = new ConcurrentHashMap<>();

    public void register(AgentRunContext context) {
        pruneExpired();
        if (context == null || context.getThreadId() == null) {
            return;
        }
        sessions.put(context.getThreadId(), context);
    }

    public Optional<AgentRunContext> get(String threadId) {
        if (threadId == null) {
            return Optional.empty();
        }
        AgentRunContext ctx = sessions.get(threadId);
        if (ctx != null && isExpired(ctx)) {
            sessions.remove(threadId);
            log.debug("[HitlSessionRegistry] Expired session evicted on access: {}", threadId);
            return Optional.empty();
        }
        return Optional.ofNullable(ctx);
    }

    public void remove(String threadId) {
        if (threadId != null) {
            sessions.remove(threadId);
        }
    }

    /** Test whether a session belongs to the given user — used by the confirm endpoint for authz. */
    public boolean belongsTo(String threadId, Long userId) {
        return get(threadId).map(ctx -> userId != null && userId.equals(ctx.getUserId())).orElse(false);
    }

    private void pruneExpired() {
        Iterator<Map.Entry<String, AgentRunContext>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AgentRunContext> e = it.next();
            if (isExpired(e.getValue())) {
                it.remove();
            }
        }
    }

    private boolean isExpired(AgentRunContext ctx) {
        return ctx == null || (System.currentTimeMillis() - ctx.getCreatedAt()) > SESSION_TTL_MS;
    }
}