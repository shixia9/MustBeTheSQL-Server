package com.sql.logic.engine.domain.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agent.core.AgentEventSinkRegistry;
import com.sql.logic.engine.domain.agent.core.AgentSseCodec;
import com.sql.logic.engine.domain.sandbox.audit.SandboxAuditService;
import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import com.sql.logic.engine.domain.sandbox.control.SandboxControlService;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionResult;
import com.sql.logic.engine.domain.sandbox.execution.ExecutionStatus;
import com.sql.logic.engine.domain.sandbox.execution.RuntimeFactory;
import com.sql.logic.engine.domain.sandbox.execution.StreamCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mock-based unit tests for {@link SandboxExecutionService} — covers the
 * agent-facing service's fail-closed semantics, AST validation gate, SSE event
 * emission, audit recording, and session lifecycle.
 */
class SandboxExecutionServiceTest {

    private RuntimeFactory runtimeFactory;
    private SandboxControlService controlService;
    private SandboxProperties properties;
    private AgentEventSinkRegistry eventSinkRegistry;
    private AgentSseCodec codec;
    private ObjectMapper objectMapper;
    private SandboxAuditService auditService;
    private SandboxExecutionService execService;

    @BeforeEach
    void setUp() {
        runtimeFactory = mock(RuntimeFactory.class);
        controlService = mock(SandboxControlService.class);
        properties = new SandboxProperties();
        eventSinkRegistry = mock(AgentEventSinkRegistry.class);
        codec = mock(AgentSseCodec.class);
        objectMapper = new ObjectMapper();
        auditService = mock(SandboxAuditService.class);

        execService = new SandboxExecutionService(runtimeFactory, controlService,
                properties, eventSinkRegistry, codec, objectMapper, auditService);

        when(runtimeFactory.isAvailable()).thenReturn(true);
        when(runtimeFactory.selectedRuntimeId()).thenReturn("docker");
        when(runtimeFactory.selectionReason()).thenReturn("Docker daemon available");
        when(codec.messageTypeForNode("SANDBOX")).thenReturn("TOOL_CALL");
    }

    // ---- executePython: input validation ----

    @Test
    void executePythonShouldRejectBlankCode() {
        ExecutionResult result = execService.executePython("thread-1", "", null, 30L, null);

        assertFalse(result.isSuccess());
        assertEquals(ExecutionStatus.ERROR, result.status());
        verifyNoInteractions(controlService);
    }

    @Test
    void executePythonShouldRejectNullCode() {
        ExecutionResult result = execService.executePython("thread-1", null, null, 30L, null);

        assertFalse(result.isSuccess());
        verifyNoInteractions(controlService);
    }

    // ---- executePython: AST validation ----

    @Test
    void executePythonShouldRejectAstViolations() {
        // os import is blacklisted by PythonAstValidator
        String maliciousCode = "import os\nos.system('rm -rf /')";

        ExecutionResult result = execService.executePython("thread-1", maliciousCode, null, 30L, null);

        assertFalse(result.isSuccess());
        assertTrue(result.stderr().contains("AST validator"));
        verifyNoInteractions(controlService);
    }

    @Test
    void executePythonShouldAcceptCleanCode() {
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-123");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenReturn(
                ExecutionResult.success("42", "", 0, 5, "python", List.of(), List.of()));

        ExecutionResult result = execService.executePython("thread-1", "print(42)", null, 30L, null);

        assertTrue(result.isSuccess());
        assertEquals("42", result.stdout());
        verify(controlService).connect(eq("python"), anyInt());
        verify(controlService).execute(eq("sbx-123"), eq("print(42)"), isNull(), any());
    }

    // ---- executePython: fail-closed ----

    @Test
    void executePythonShouldFailClosedWhenNoRuntime() {
        when(runtimeFactory.isAvailable()).thenReturn(false);
        when(runtimeFactory.selectionReason()).thenReturn("No container runtime");

        ExecutionResult result = execService.executePython("thread-1", "print(1)", null, 30L, null);

        assertFalse(result.isSuccess());
        assertTrue(result.stderr().contains("No sandbox runtime"));
        verifyNoInteractions(controlService);
    }

    // ---- executePython: session reuse ----

    @Test
    void executePythonShouldReuseSessionForSameThread() {
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-reuse");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenReturn(
                ExecutionResult.success("1", "", 0, 1, "python", List.of(), List.of()));

        execService.executePython("thread-1", "print(1)", null, 30L, null);
        execService.executePython("thread-1", "print(2)", null, 30L, null);

        // connect should only be called once (session reused)
        verify(controlService, times(1)).connect(anyString(), anyInt());
        verify(controlService, times(2)).execute(eq("sbx-reuse"), anyString(), any(), any());
    }

    // ---- executePython: SSE emission ----

    @Test
    void executePythonShouldEmitSseEventsWhenSinkRegistered() {
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        when(eventSinkRegistry.get("thread-1")).thenReturn(sink);
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-sse");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            StreamCallback callback = invocation.getArgument(3);
            if (callback != null) {
                callback.onLine("hello world", false);
            }
            return ExecutionResult.success("hello world", "", 0, 10, "python", List.of(), List.of());
        });

        java.util.List<String> emitted = new java.util.concurrent.CopyOnWriteArrayList<>();
        sink.asFlux().subscribe(emitted::add);

        execService.executePython("thread-1", "print('hello world')", null, 30L, null);

        // Should have emitted at least STARTED + stream chunk(s) + FINISHED
        assertFalse(emitted.isEmpty());
        // Verify STARTED event
        assertTrue(emitted.stream().anyMatch(e -> e.contains("STARTED")));
        // Verify FINISHED event
        assertTrue(emitted.stream().anyMatch(e -> e.contains("FINISHED")));
        // Verify stream chunk
        assertTrue(emitted.stream().anyMatch(e -> e.contains("stream")));
    }

    @Test
    void executePythonShouldNotEmitSseWhenNoSinkRegistered() {
        when(eventSinkRegistry.get("thread-1")).thenReturn(null);
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-nosink");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenReturn(
                ExecutionResult.success("ok", "", 0, 1, "python", List.of(), List.of()));

        ExecutionResult result = execService.executePython("thread-1", "print(1)", null, 30L, null);

        assertTrue(result.isSuccess());
        // No sink → emitSandboxEvent probes the registry for each event (STARTED,
        // FINISHED) but returns early without emitting. Verify it was probed.
        verify(eventSinkRegistry, atLeastOnce()).get("thread-1");
    }

    // ---- executePython: audit ----

    @Test
    void executePythonShouldRecordAuditOnSuccess() {
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-audit");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenReturn(
                ExecutionResult.success("ok", "", 0, 5, "python", List.of(), List.of()));

        execService.executePython("thread-1", "print(1)", null, 30L, null);

        verify(auditService).recordAsync(eq("thread-1"), eq("sbx-audit"), eq("python"),
                eq("docker"), eq("print(1)"), any(ExecutionResult.class));
    }

    @Test
    void executePythonShouldRecordAuditOnFailure() {
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-fail");
        when(controlService.execute(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("Container exploded"));

        ExecutionResult result = execService.executePython("thread-1", "print(1)", null, 30L, null);

        assertFalse(result.isSuccess());
        verify(auditService).recordAsync(eq("thread-1"), anyString(), eq("python"),
                anyString(), anyString(), any(ExecutionResult.class));
    }

    // ---- executeShell ----

    @Test
    void executeShellShouldRejectBlankCode() {
        ExecutionResult result = execService.executeShell("thread-1", "", 30L, null);

        assertFalse(result.isSuccess());
        verifyNoInteractions(controlService);
    }

    @Test
    void executeShellShouldFailClosedWhenNoRuntime() {
        when(runtimeFactory.isAvailable()).thenReturn(false);

        ExecutionResult result = execService.executeShell("thread-1", "echo hello", 30L, null);

        assertFalse(result.isSuccess());
        verifyNoInteractions(controlService);
    }

    @Test
    void executeShellShouldDelegateToControlService() {
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-shell");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenReturn(
                ExecutionResult.success("hello\n", "", 0, 1, "bash", List.of(), List.of()));

        ExecutionResult result = execService.executeShell("thread-1", "echo hello", 30L, null);

        assertTrue(result.isSuccess());
        verify(controlService).connect(eq("bash"), anyInt());
    }

    // ---- destroyThreadSession ----

    @Test
    void destroyThreadSessionShouldDisconnect() {
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-destroy");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenReturn(
                ExecutionResult.success("1", "", 0, 1, "python", List.of(), List.of()));

        execService.executePython("thread-1", "print(1)", null, 30L, null);
        execService.destroyThreadSession("thread-1");

        verify(controlService).disconnect("sbx-destroy");
    }

    @Test
    void destroyThreadSessionShouldBeNoOpForUnknownThread() {
        execService.destroyThreadSession("unknown-thread");
        verifyNoInteractions(controlService);
    }

    // ---- stdin piping ----

    @Test
    void executePythonShouldPipeNonTrivialInputJsonAsStdin() {
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-stdin");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenReturn(
                ExecutionResult.success("result", "", 0, 1, "python", List.of(), List.of()));

        execService.executePython("thread-1", "print(input())", "{\"key\":\"value\"}", 30L, null);

        org.mockito.ArgumentCaptor<byte[]> stdinCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(controlService).execute(anyString(), anyString(), stdinCaptor.capture(), any());
        assertNotNull(stdinCaptor.getValue());
        assertEquals("{\"key\":\"value\"}", new String(stdinCaptor.getValue()));
    }

    @Test
    void executePythonShouldNotPipeTrivialInputJson() {
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-nostdin");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenReturn(
                ExecutionResult.success("ok", "", 0, 1, "python", List.of(), List.of()));

        execService.executePython("thread-1", "print(1)", "{}", 30L, null);

        org.mockito.ArgumentCaptor<byte[]> stdinCaptor = org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(controlService).execute(anyString(), anyString(), stdinCaptor.capture(), any());
        assertNull(stdinCaptor.getValue());
    }

    // ---- diagnostics ----

    @Test
    void isSandboxAvailableShouldReflectRuntimeFactory() {
        when(runtimeFactory.isAvailable()).thenReturn(true);
        assertTrue(execService.isSandboxAvailable());

        when(runtimeFactory.isAvailable()).thenReturn(false);
        assertFalse(execService.isSandboxAvailable());
    }

    @Test
    void activeThreadSessionCountShouldTrackSessions() {
        when(controlService.connect(anyString(), anyInt())).thenReturn("sbx-count");
        when(controlService.execute(anyString(), anyString(), any(), any())).thenReturn(
                ExecutionResult.success("1", "", 0, 1, "python", List.of(), List.of()));

        assertEquals(0, execService.activeThreadSessionCount());
        execService.executePython("t1", "print(1)", null, 30L, null);
        assertEquals(1, execService.activeThreadSessionCount());
        execService.executePython("t2", "print(2)", null, 30L, null);
        assertEquals(2, execService.activeThreadSessionCount());
    }
}
