package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.display.DisplayResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VNC/GUI support — Phase 2 of the DB-GPT parity refactor.
 *
 * <p>Tests three concerns:
 * <ul>
 *   <li>{@link ContainerCliSandboxSession#parseVncPort(String)} — Docker/Podman
 *       inspect JSON port parsing (fixed + dynamic bindings, malformed JSON).</li>
 *   <li>{@link DisplayResult#vnc(String, long)} — the VNC display factory carries
 *       the guiUrl and a success status.</li>
 *   <li>{@link ExecutionResult#vnc(String, long, String)} — the VNC execution
 *       factory wraps the display result with correct status/language.</li>
 * </ul>
 */
class VncSupportTest {

    // ---- parseVncPort ----

    @Test
    void shouldParseFixedPortBinding() {
        String json = "{\"6080/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"6080\"}],"
                + "\"5900/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"5900\"}]}";
        assertEquals("http://localhost:6080/vnc.html", ContainerCliSandboxSession.parseVncPort(json));
    }

    @Test
    void shouldParseDynamicPortBinding() {
        // Docker dynamic port: HostPort is randomly assigned (e.g. 32768)
        String json = "{\"6080/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"32768\"}]}";
        assertEquals("http://localhost:32768/vnc.html", ContainerCliSandboxSession.parseVncPort(json));
    }

    @Test
    void shouldReturnNullWhenNo6080Port() {
        String json = "{\"5900/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"5900\"}]}";
        assertNull(ContainerCliSandboxSession.parseVncPort(json));
    }

    @Test
    void shouldReturnNullForNullJson() {
        assertNull(ContainerCliSandboxSession.parseVncPort(null));
    }

    @Test
    void shouldReturnNullForBlankJson() {
        assertNull(ContainerCliSandboxSession.parseVncPort(""));
        assertNull(ContainerCliSandboxSession.parseVncPort("   "));
    }

    @Test
    void shouldReturnNullForLiteralNull() {
        // Docker inspect can return the string "null" when no ports are published
        assertNull(ContainerCliSandboxSession.parseVncPort("null"));
    }

    @Test
    void shouldHandlePortEntryWithoutHostPort() {
        // Edge case: port entry exists but HostPort is empty (container not yet bound)
        String json = "{\"6080/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"\"}]}";
        assertNull(ContainerCliSandboxSession.parseVncPort(json));
    }

    // ---- DisplayResult.vnc ----

    @Test
    void displayResultVncShouldCarryGuiUrl() {
        DisplayResult dr = DisplayResult.vnc("http://localhost:6080/vnc.html", 5);
        assertEquals("success", dr.status());
        assertEquals("GUI container started", dr.output());
        assertEquals("", dr.error());
        assertEquals(0, dr.exitCode());
        assertEquals(5, dr.executionTimeMs());
        assertEquals("http://localhost:6080/vnc.html", dr.guiUrl());
        assertTrue(dr.files().isEmpty());
        assertTrue(dr.logs().isEmpty());
        assertTrue(dr.screenshots().isEmpty());
    }

    // ---- ExecutionResult.vnc ----

    @Test
    void executionResultVncShouldWrapDisplayResult() {
        ExecutionResult er = ExecutionResult.vnc("http://localhost:32768/vnc.html", 10, "python-vnc");
        assertTrue(er.isSuccess());
        assertEquals(ExecutionStatus.SUCCESS, er.status());
        assertEquals("python-vnc", er.language());
        assertEquals(0, er.exitCode());
        assertEquals(10, er.durationMs());
        assertNotNull(er.displayResult());
        assertEquals("http://localhost:32768/vnc.html", er.displayResult().guiUrl());
        assertEquals("success", er.displayResult().status());
    }

    @Test
    void executionResultVncShouldNotTimeout() {
        ExecutionResult er = ExecutionResult.vnc("http://localhost:6080/vnc.html", 0, "python-vnc");
        assertFalse(er.timedOut());
    }
}
