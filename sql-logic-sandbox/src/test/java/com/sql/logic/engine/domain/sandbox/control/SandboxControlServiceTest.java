package com.sql.logic.engine.domain.sandbox.control;

import com.sql.logic.engine.common.dto.SandboxMethodsResponse;
import com.sql.logic.engine.common.dto.SandboxSessionResponse;
import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import com.sql.logic.engine.domain.sandbox.display.DisplayLayer;
import com.sql.logic.engine.domain.sandbox.display.DisplayResult;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionResult;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionStatus;
import com.sql.logic.engine.domain.sandbox.execution.RuntimeFactory;
import com.sql.logic.engine.domain.sandbox.execution.SandboxRuntime;
import com.sql.logic.engine.domain.sandbox.execution.SandboxSession;
import com.sql.logic.engine.domain.sandbox.execution.SessionConfig;
import com.sql.logic.engine.domain.sandbox.execution.StreamCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mock-based unit tests for {@link SandboxControlService} — covers the control
 * layer's task dispatch, session lifecycle, display-layer recording, and serial
 * lock semantics without requiring a real Docker daemon.
 */
class SandboxControlServiceTest {

    private RuntimeFactory runtimeFactory;
    private SandboxRuntime runtime;
    private SandboxSession session;
    private SandboxProperties properties;
    private DisplayLayer displayLayer;
    private SandboxControlService controlService;

    @BeforeEach
    void setUp() {
        runtimeFactory = mock(RuntimeFactory.class);
        runtime = mock(SandboxRuntime.class);
        session = mock(SandboxSession.class);
        properties = new SandboxProperties();
        displayLayer = new DisplayLayer();
        controlService = new SandboxControlService(runtimeFactory, properties, displayLayer);

        when(runtimeFactory.getRuntime()).thenReturn(runtime);
        when(runtimeFactory.isAvailable()).thenReturn(true);
        when(runtimeFactory.selectedRuntimeId()).thenReturn("docker");
        when(runtimeFactory.selectionReason()).thenReturn("Docker daemon available");
    }

    // ---- connect ----

    @Test
    void connectShouldCreateSessionWithCorrectConfig() {
        when(runtime.createSession(anyString(), any(SessionConfig.class))).thenReturn(session);

        String sessionId = controlService.connect("python", 30);

        assertNotNull(sessionId);
        assertTrue(sessionId.startsWith("sbx-"));

        ArgumentCaptor<SessionConfig> configCaptor = ArgumentCaptor.forClass(SessionConfig.class);
        verify(runtime).createSession(eq(sessionId), configCaptor.capture());
        SessionConfig config = configCaptor.getValue();
        assertEquals("python", config.language());
        assertEquals(30, config.timeoutSeconds());
        assertTrue(config.networkDisabled());
    }

    @Test
    void connectShouldUseDefaultLanguageWhenBlank() {
        when(runtime.createSession(anyString(), any(SessionConfig.class))).thenReturn(session);
        properties.setDefaultLanguage("python");

        String sessionId = controlService.connect("", null);

        assertNotNull(sessionId);
        ArgumentCaptor<SessionConfig> configCaptor = ArgumentCaptor.forClass(SessionConfig.class);
        verify(runtime).createSession(eq(sessionId), configCaptor.capture());
        assertEquals("python", configCaptor.getValue().language());
    }

    // ---- execute ----

    @Test
    void executeShouldDelegateToSessionAndRecordDisplay() {
        when(runtime.createSession(anyString(), any())).thenReturn(session);
        when(session.isActive()).thenReturn(true);
        when(runtime.getSession(anyString())).thenReturn(session);

        ExecutionResult expectedResult = ExecutionResult.success("hello", "", 0, 10, "python", List.of(), List.of());
        when(session.execute(anyString(), any())).thenReturn(expectedResult);

        String sessionId = controlService.connect("python", 30);
        ExecutionResult result = controlService.execute(sessionId, "print('hello')", null);

        assertTrue(result.isSuccess());
        assertEquals("hello", result.stdout());
        verify(session).execute(eq("print('hello')"), isNull());
        // Display layer should have recorded the result.
        assertNotNull(displayLayer.getLastResult(sessionId));
    }

    @Test
    void executeShouldThrowForUnknownSession() {
        when(runtime.getSession("unknown")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> controlService.execute("unknown", "print(1)", null));
    }

    @Test
    void executeShouldThrowForInactiveSession() {
        when(runtime.getSession("inactive")).thenReturn(session);
        when(session.isActive()).thenReturn(false);

        assertThrows(IllegalArgumentException.class,
                () -> controlService.execute("inactive", "print(1)", null));
    }

    // ---- configure ----

    @Test
    void configureShouldInstallDependenciesAndRecordDisplay() {
        when(runtime.createSession(anyString(), any())).thenReturn(session);
        when(session.isActive()).thenReturn(true);
        when(runtime.getSession(anyString())).thenReturn(session);

        ExecutionResult installResult = ExecutionResult.success("installed: pandas", "", 0, 5, "python", List.of(), List.of());
        when(session.installDependencies(anyList())).thenReturn(installResult);

        String sessionId = controlService.connect("python", 30);
        ExecutionResult result = controlService.configure(sessionId, List.of("pandas"), null);

        assertTrue(result.isSuccess());
        verify(session).installDependencies(List.of("pandas"));
        assertNotNull(displayLayer.getLastResult(sessionId));
    }

    @Test
    void configureShouldApplyConfigInfoBeforeInstall() {
        when(runtime.createSession(anyString(), any())).thenReturn(session);
        when(session.isActive()).thenReturn(true);
        when(runtime.getSession(anyString())).thenReturn(session);
        when(session.installDependencies(anyList())).thenReturn(
                ExecutionResult.success("ok", "", 0, 1, "python", List.of(), List.of()));

        String sessionId = controlService.connect("python", 30);
        Map<String, Object> configInfo = Map.of(
                "env", Map.of("PIP_INDEX_URL", "https://pypi.org/simple"),
                "timeout", 60);

        controlService.configure(sessionId, List.of("numpy"), configInfo);

        verify(session).applyConfig(anyMap(), eq(60));
        verify(session).installDependencies(List.of("numpy"));
    }

    // ---- disconnect ----

    @Test
    void disconnectShouldDestroySessionAndClearDisplayHistory() {
        when(runtime.createSession(anyString(), any())).thenReturn(session);
        when(session.isActive()).thenReturn(true);
        when(runtime.getSession(anyString())).thenReturn(session);
        when(runtime.destroySession(anyString())).thenReturn(true);

        String sessionId = controlService.connect("python", 30);
        // Record something in display layer first.
        displayLayer.addResult(sessionId, DisplayResult.success("out", 1, 0, List.of()));
        assertNotNull(displayLayer.getLastResult(sessionId));

        boolean destroyed = controlService.disconnect(sessionId);

        assertTrue(destroyed);
        verify(runtime).destroySession(sessionId);
        // Display history should be cleared.
        assertNull(displayLayer.getLastResult(sessionId));
    }

    @Test
    void disconnectShouldReturnFalseForUnknownSession() {
        when(runtime.destroySession("unknown")).thenReturn(false);
        boolean destroyed = controlService.disconnect("unknown");
        assertFalse(destroyed);
    }

    // ---- manual ----

    @Test
    void manualShouldConnectExecuteAndDisconnect() {
        when(runtime.createSession(anyString(), any())).thenReturn(session);
        when(session.isActive()).thenReturn(true);
        when(runtime.getSession(anyString())).thenReturn(session);
        when(runtime.destroySession(anyString())).thenReturn(true);
        // manual() → execute(sessionId, code, callback) → session.execute(code, callback)
        // The 2-arg session.execute(String, StreamCallback) is abstract — mock it directly.
        // Use nullable() because manual() passes null for the callback.
        when(session.execute(anyString(), nullable(StreamCallback.class))).thenReturn(
                ExecutionResult.success("42", "", 0, 5, "python", List.of(), List.of()));

        ExecutionResult result = controlService.manual("python", "print(42)", 30);

        assertTrue(result.isSuccess());
        assertEquals("42", result.stdout());
        // Should have created and destroyed exactly one session.
        verify(runtime, times(1)).createSession(anyString(), any());
        verify(runtime, times(1)).destroySession(anyString());
    }

    // ---- status ----

    @Test
    void statusShouldIncludeRuntimeHealthAndSessionStats() {
        when(runtime.healthCheck()).thenReturn(new java.util.LinkedHashMap<>(Map.of("status", "healthy")));
        when(runtime.listSessions()).thenReturn(List.of());
        when(runtimeFactory.selectedRuntimeId()).thenReturn("docker");

        Map<String, Object> status = controlService.status();

        assertEquals("docker", status.get("selectedRuntime"));
        assertEquals(0, status.get("activeSessionCount"));
        assertNotNull(status.get("sessions"));
    }

    @Test
    void statusShouldCollectPerSessionStats() {
        when(runtime.healthCheck()).thenReturn(new java.util.LinkedHashMap<>(Map.of("status", "healthy")));
        when(runtime.listSessions()).thenReturn(List.of("s1"));
        when(runtime.getSession("s1")).thenReturn(session);
        when(session.isActive()).thenReturn(true);
        when(session.config()).thenReturn(SessionConfig.builder("python").build());
        when(session.collectStats()).thenReturn(Map.of("cpuUsage", "0.5%", "memoryUsage", "50MiB / 256MiB"));

        Map<String, Object> status = controlService.status();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sessions = (List<Map<String, Object>>) status.get("sessions");
        assertEquals(1, sessions.size());
        assertEquals("s1", sessions.get(0).get("sessionId"));
        assertEquals("0.5%", sessions.get(0).get("cpuUsage"));
    }

    // ---- list ----

    @Test
    void listShouldReturnSessionResponses() {
        when(runtime.listSessions()).thenReturn(List.of("s1", "s2"));
        when(runtime.getSession("s1")).thenReturn(session);
        when(runtime.getSession("s2")).thenReturn(session);
        when(session.sessionId()).thenReturn("s1");
        when(session.config()).thenReturn(SessionConfig.builder("python").build());
        when(session.isActive()).thenReturn(true);
        when(session.createdAt()).thenReturn(1000L);
        when(session.lastAccessed()).thenReturn(2000L);

        List<SandboxSessionResponse> responses = controlService.list();

        assertEquals(2, responses.size());
    }

    @Test
    void listShouldSkipNullSessions() {
        when(runtime.listSessions()).thenReturn(List.of("s1", "s2"));
        when(runtime.getSession("s1")).thenReturn(session);
        when(runtime.getSession("s2")).thenReturn(null);
        when(session.sessionId()).thenReturn("s1");
        when(session.config()).thenReturn(SessionConfig.builder("python").build());
        when(session.isActive()).thenReturn(true);

        List<SandboxSessionResponse> responses = controlService.list();

        assertEquals(1, responses.size());
    }

    // ---- methods ----

    @Test
    void methodsShouldReturnCapabilities() {
        SandboxMethodsResponse response = controlService.methods();

        assertNotNull(response);
        assertEquals("docker", response.getSelectedRuntime());
        assertFalse(response.isVncSupported()); // vncEnabled defaults to false
        assertTrue(response.isFileTransferSupported());
        assertNotNull(response.getSupportedLanguages());
        assertTrue(response.getSupportedLanguages().contains("python"));
    }

    @Test
    void methodsShouldReportVncSupportedWhenEnabled() {
        properties.setVncEnabled(true);
        SandboxMethodsResponse response = controlService.methods();
        assertTrue(response.isVncSupported());
    }

    // ---- getLastResult ----

    @Test
    void getLastResultShouldReturnNullForUnknownSession() {
        assertNull(controlService.getLastResult("unknown"));
    }

    @Test
    void getLastResultShouldReturnRecordedResult() {
        when(runtime.createSession(anyString(), any())).thenReturn(session);
        when(session.isActive()).thenReturn(true);
        when(runtime.getSession(anyString())).thenReturn(session);
        when(session.execute(anyString(), any())).thenReturn(
                ExecutionResult.success("out", "", 0, 1, "python", List.of(), List.of()));

        String sessionId = controlService.connect("python", 30);
        controlService.execute(sessionId, "print('out')", null);

        DisplayResult last = controlService.getLastResult(sessionId);
        assertNotNull(last);
        assertEquals("success", last.status());
    }

    // ---- getFile ----

    @Test
    void getFileShouldDelegateAndRecordDisplay() {
        when(runtime.createSession(anyString(), any())).thenReturn(session);
        when(session.isActive()).thenReturn(true);
        when(runtime.getSession(anyString())).thenReturn(session);
        DisplayResult fileResult = DisplayResult.of("success", "file content", "", 1, 0, List.of("data.csv"));
        when(session.getFileContent("data.csv")).thenReturn(fileResult);

        String sessionId = controlService.connect("python", 30);
        DisplayResult result = controlService.getFile(sessionId, "data.csv");

        assertNotNull(result);
        assertEquals("file content", result.output());
        verify(session).getFileContent("data.csv");
    }
}
