package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import com.sql.logic.engine.domain.sandbox.display.DisplayResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link DockerSandboxRuntime} — requires a running Docker
 * daemon. Guarded by {@code DOCKER_AVAILABLE=true} env var so it only runs in
 * environments with Docker (CI, dev machines with Docker Desktop).
 *
 * <p>Tests the full stateful session lifecycle: create → execute (multiple) →
 * installDependencies (persistence) → getFileContent → timeout → destroy.
 * Multi-language coverage: Python + JavaScript.
 *
 * <p>To run locally: {@code DOCKER_AVAILABLE=true mvn -pl sql-logic-service test
 * -Dtest=DockerSandboxRuntimeIntegrationTest}
 */
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
class DockerSandboxRuntimeIntegrationTest {

    private DockerSandboxRuntime runtime;
    private SandboxProperties properties;
    private String sessionId;

    @BeforeEach
    void setUp() {
        properties = new SandboxProperties();
        runtime = new DockerSandboxRuntime(properties);
        // Skip if Docker daemon is not reachable (double-check beyond the env var).
        org.junit.jupiter.api.Assumptions.assumeTrue(runtime.isDockerAvailable(),
                "Docker daemon not reachable — skipping integration test");
    }

    @AfterEach
    void tearDown() {
        if (runtime != null && sessionId != null) {
            try {
                runtime.destroySession(sessionId);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    void shouldCreateAndExecutePythonCode() {
        sessionId = "itest-" + System.currentTimeMillis();
        SessionConfig config = SessionConfig.builder("python")
                .timeoutSeconds(15)
                .build();

        SandboxSession session = runtime.createSession(sessionId, config);
        assertNotNull(session);
        assertTrue(session.isActive());

        ExecutionResult result = session.execute("print('hello integration test')", null);
        assertTrue(result.isSuccess());
        assertTrue(result.stdout().contains("hello integration test"));
        assertEquals(0, result.exitCode());
    }

    @Test
    void shouldMaintainStateAcrossMultipleExecutions() {
        sessionId = "itest-state-" + System.currentTimeMillis();
        SessionConfig config = SessionConfig.builder("python").timeoutSeconds(15).build();
        SandboxSession session = runtime.createSession(sessionId, config);

        // First execution: create a file
        session.execute("with open('/workspace/test.txt', 'w') as f: f.write('data')", null);

        // Second execution: read the file (proves stateful session)
        ExecutionResult result = session.execute(
                "print(open('/workspace/test.txt').read())", null);
        assertTrue(result.isSuccess());
        assertTrue(result.stdout().contains("data"));
    }

    @Test
    void shouldInstallPipDependenciesAndUseThem() {
        sessionId = "itest-deps-" + System.currentTimeMillis();
        SessionConfig config = SessionConfig.builder("python").timeoutSeconds(30).build();
        SandboxSession session = runtime.createSession(sessionId, config);

        // Install a small package
        ExecutionResult installResult = session.installDependencies(List.of("six"));
        assertNotNull(installResult);

        // Use the installed package
        ExecutionResult result = session.execute(
                "import six\nprint(six.__version__)", null);
        assertTrue(result.isSuccess(), "Should be able to import installed package");
    }

    @Test
    void shouldExecuteJavaScriptCode() {
        sessionId = "itest-js-" + System.currentTimeMillis();
        SessionConfig config = SessionConfig.builder("javascript").timeoutSeconds(15).build();
        SandboxSession session = runtime.createSession(sessionId, config);

        ExecutionResult result = session.execute("console.log('hello from node')", null);
        assertTrue(result.isSuccess());
        assertTrue(result.stdout().contains("hello from node"));
    }

    @Test
    void shouldHandleExecutionTimeout() {
        sessionId = "itest-timeout-" + System.currentTimeMillis();
        SessionConfig config = SessionConfig.builder("python").timeoutSeconds(2).build();
        SandboxSession session = runtime.createSession(sessionId, config);

        // Infinite loop should timeout
        ExecutionResult result = session.execute("while True: pass", null);
        assertNotNull(result);
        // The result should indicate timeout (status != success)
        assertNotEquals(ExecutionStatus.SUCCESS, result.status());
    }

    @Test
    void shouldGetFileContent() {
        sessionId = "itest-file-" + System.currentTimeMillis();
        SessionConfig config = SessionConfig.builder("python").timeoutSeconds(15).build();
        SandboxSession session = runtime.createSession(sessionId, config);

        // Create a file in the workspace
        session.execute("with open('/workspace/output.txt', 'w') as f: f.write('file content here')", null);

        // Retrieve the file content
        DisplayResult fileResult = session.getFileContent("output.txt");
        assertNotNull(fileResult);
        assertTrue(fileResult.output().contains("file content here"));
    }

    @Test
    void shouldDestroySessionAndCleanup() {
        sessionId = "itest-destroy-" + System.currentTimeMillis();
        SessionConfig config = SessionConfig.builder("python").timeoutSeconds(10).build();
        runtime.createSession(sessionId, config);

        assertTrue(runtime.listSessions().contains(sessionId));

        boolean destroyed = runtime.destroySession(sessionId);
        assertTrue(destroyed);
        assertFalse(runtime.listSessions().contains(sessionId));

        // Prevent tearDown from trying to destroy again
        sessionId = null;
    }

    @Test
    void shouldReportHealthCheck() {
        java.util.Map<String, Object> health = runtime.healthCheck();
        assertNotNull(health);
        // Health check should include a status key
        assertTrue(health.containsKey("status") || health.containsKey("dockerVersion"));
    }

    @Test
    void shouldSupportMultipleLanguages() {
        for (String lang : new String[]{"python", "javascript"}) {
            String sid = "itest-lang-" + lang + "-" + System.currentTimeMillis();
            try {
                SessionConfig config = SessionConfig.builder(lang).timeoutSeconds(15).build();
                SandboxSession session = runtime.createSession(sid, config);
                assertTrue(session.isActive());
                runtime.destroySession(sid);
            } finally {
                // Cleanup handled in destroySession
            }
        }
    }
}
