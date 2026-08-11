package com.sql.logic.engine.domain.sandbox.display;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DisplayLayer} — Phase 3 display-layer history service.
 *
 * <p>Verifies per-session history accumulation, FIFO eviction at the cap,
 * getLastResult, listHistory, and clear semantics.
 */
class DisplayLayerTest {

    private DisplayResult sample(String status) {
        return DisplayResult.of(status, "output-" + System.nanoTime(), "",
                10, 0, List.of("file.py"));
    }

    @Test
    void shouldReturnNullForUnknownSession() {
        DisplayLayer layer = new DisplayLayer();
        assertNull(layer.getLastResult("no-such-session"));
        assertTrue(layer.listHistory("no-such-session").isEmpty());
    }

    @Test
    void shouldAccumulateResultsPerSession() {
        DisplayLayer layer = new DisplayLayer();
        DisplayResult r1 = sample("success");
        DisplayResult r2 = sample("success");

        layer.addResult("s1", r1);
        layer.addResult("s1", r2);

        assertEquals(2, layer.listHistory("s1").size());
        assertSame(r2, layer.getLastResult("s1"));
    }

    @Test
    void shouldIsolateSessions() {
        DisplayLayer layer = new DisplayLayer();
        DisplayResult r1 = sample("success");
        DisplayResult r2 = sample("error");

        layer.addResult("s1", r1);
        layer.addResult("s2", r2);

        assertEquals(1, layer.listHistory("s1").size());
        assertEquals(1, layer.listHistory("s2").size());
        assertSame(r1, layer.getLastResult("s1"));
        assertSame(r2, layer.getLastResult("s2"));
    }

    @Test
    void shouldEvictOldestAtCap() {
        DisplayLayer layer = new DisplayLayer();
        // Add MAX_HISTORY_PER_SESSION + 10 results — oldest should be evicted.
        for (int i = 0; i < DisplayLayer.MAX_HISTORY_PER_SESSION + 10; i++) {
            layer.addResult("s1", sample("success"));
        }
        List<DisplayLayer.DisplayResultRecord> history = layer.listHistory("s1");
        assertEquals(DisplayLayer.MAX_HISTORY_PER_SESSION, history.size());
    }

    @Test
    void shouldClearOnDisconnect() {
        DisplayLayer layer = new DisplayLayer();
        layer.addResult("s1", sample("success"));
        assertEquals(1, layer.listHistory("s1").size());

        layer.clear("s1");
        assertTrue(layer.listHistory("s1").isEmpty());
        assertNull(layer.getLastResult("s1"));
    }

    @Test
    void shouldIgnoreNullArgs() {
        DisplayLayer layer = new DisplayLayer();
        layer.addResult(null, sample("success"));
        layer.addResult("s1", null);
        assertEquals(0, layer.activeSessionCount());
        assertNull(layer.getLastResult(null));
        assertTrue(layer.listHistory(null).isEmpty());
        layer.clear(null); // no-op, no exception
    }

    @Test
    void shouldTrackActiveSessionCount() {
        DisplayLayer layer = new DisplayLayer();
        layer.addResult("s1", sample("success"));
        layer.addResult("s2", sample("success"));
        assertEquals(2, layer.activeSessionCount());

        layer.clear("s1");
        assertEquals(1, layer.activeSessionCount());
    }
}
