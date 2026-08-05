package com.sql.logic.engine.domain.sandbox.display;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Display-layer history service.
 *
 * <p>Maintains a bounded per-session history of {@link DisplayResult}s so the
 * frontend can render prior execution results (stdout, files, guiUrl, logs)
 * without re-running the code. History is capped at {@link #MAX_HISTORY_PER_SESSION}
 * entries per session (FIFO eviction) and cleared on {@link #clear(String)}.
 *
 * <p>This is the "memory" of the display layer: the control layer calls
 * {@link #addResult} after each execute/configure/getFile operation, and the
 * REST/SSE layer can query {@link #getLastResult} or {@link #listHistory} to
 * surface the full execution timeline.
 */
@Service
public class DisplayLayer {

    private static final Logger log = LoggerFactory.getLogger(DisplayLayer.class);

    /** Max results retained per session (FIFO). */
    static final int MAX_HISTORY_PER_SESSION = 50;

    private final ConcurrentHashMap<String, List<DisplayResultRecord>> history = new ConcurrentHashMap<>();

    /**
     * Append a result to the session's history. Evicts the oldest entry when the
     * per-session cap is reached.
     *
     * @param sessionId the sandbox session id
     * @param result    the display result to record
     */
    public void addResult(String sessionId, DisplayResult result) {
        if (sessionId == null || result == null) {
            return;
        }
        List<DisplayResultRecord> records = history.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (records) {
            records.add(new DisplayResultRecord(sessionId, result, System.currentTimeMillis()));
            // FIFO eviction when over cap.
            while (records.size() > MAX_HISTORY_PER_SESSION) {
                records.removeFirst();
            }
        }
        log.debug("[DisplayLayer] Recorded result for session {} (status={}, total={})",
                sessionId, result.status(), records.size());
    }

    /** Get the most recent result for a session (null if none). */
    public DisplayResult getLastResult(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        List<DisplayResultRecord> records = history.get(sessionId);
        if (records == null || records.isEmpty()) {
            return null;
        }
        synchronized (records) {
            return records.isEmpty() ? null : records.get(records.size() - 1).result();
        }
    }

    /** List the full history for a session (immutable copy; empty if none). */
    public List<DisplayResultRecord> listHistory(String sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        List<DisplayResultRecord> records = history.get(sessionId);
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    /** Clear history for a session (called on disconnect). */
    public void clear(String sessionId) {
        if (sessionId == null) {
            return;
        }
        history.remove(sessionId);
        log.debug("[DisplayLayer] Cleared history for session {}", sessionId);
    }

    /** Total active sessions with history (for monitoring). */
    public int activeSessionCount() {
        return history.size();
    }

    /**
     * Immutable record of one display-layer event.
     *
     * @param sessionId the sandbox session id
     * @param result    the display result
     * @param timestamp epoch millis when the result was recorded
     */
    public record DisplayResultRecord(String sessionId, DisplayResult result, long timestamp) {
    }
}
