package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxConfig;
import com.sql.logic.engine.domain.sandbox.display.DisplayResult;
import com.sql.logic.engine.domain.sandbox.util.ProcessManager;
import com.sql.logic.engine.domain.sandbox.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Local-process sandbox session.
 *
 * <p>Executes code directly on the host via {@link ProcessBuilder}. This runtime is
 * <b>opt-in only</b> ({@code sandbox.allow-local-runtime=true}) because host
 * execution has weaker isolation than containers. Security is provided by:
 * <ol>
 *   <li>{@link SecurityUtils#validateCode} — pattern blacklist before execution
 *       (hard gate: code is rejected if any dangerous pattern is found).</li>
 *   <li>{@link ProcessManager#killProcessTree} — recursive process-tree kill on
 *       timeout, preventing orphaned children.</li>
 * </ol>
 *
 * <p>Like the Docker session, this is stateful: a persistent working directory is
 * created per session, so files written in one execution are visible in the next.
 * Dependency installation uses {@code pip install --target <workdir>/.packages}
 * (Python) or {@code npm install} (JavaScript), with PYTHONPATH preset.
 */
public class LocalSandboxSession implements SandboxSession {

    private static final Logger log = LoggerFactory.getLogger(LocalSandboxSession.class);

    private final String sessionId;
    private final SessionConfig config;
    private final String workDir;
    private final boolean customWorkDir;

    private volatile boolean active = false;
    private final long createdAt = System.currentTimeMillis();
    private volatile long lastAccessed = createdAt;
    private final List<Long> processPool = new ArrayList<>();

    public LocalSandboxSession(String sessionId, SessionConfig config) {
        this.sessionId = sessionId;
        this.config = config;
        // Use config.workingDir if it's an explicit absolute path (not the default
        // /workspace which doesn't exist on the host). Otherwise create a temp dir.
        String cwd = config.workingDir();
        if (cwd != null && !cwd.equals(SandboxConfig.WORKING_DIR) && new File(cwd).isAbsolute()) {
            this.workDir = cwd;
            this.customWorkDir = true;
        } else {
            this.workDir = null; // assigned in start()
            this.customWorkDir = false;
        }
    }

    // The workDir field is effectively final after start(); use a holder for the
    // temp-dir case since the constructor can't call Files.createTempDirectory
    // (throws IOException) without try/catch bloat.
    private String resolvedWorkDir;

    // ========================================================================
    //  Lifecycle
    // ========================================================================

    @Override
    public boolean start() {
        try {
            if (customWorkDir) {
                resolvedWorkDir = workDir;
                new File(resolvedWorkDir).mkdirs();
            } else {
                resolvedWorkDir = Files.createTempDirectory("sandbox_" + sessionId + "_").toString();
            }
            // Set up Python package target dir.
            if (config.language().startsWith("python")) {
                new File(resolvedWorkDir, ".packages").mkdirs();
            }
            this.active = true;
            this.lastAccessed = System.currentTimeMillis();
            log.info("[LocalSandbox] Session {} started (workDir={})", sessionId, resolvedWorkDir);
            return true;
        } catch (IOException e) {
            log.error("[LocalSandbox] Failed to start session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean stop() {
        // Kill any lingering processes.
        synchronized (processPool) {
            for (Long pid : new ArrayList<>(processPool)) {
                ProcessManager.killProcessTree(pid);
            }
            processPool.clear();
        }
        // Clean up temp dir (not custom dirs).
        if (!customWorkDir && resolvedWorkDir != null) {
            deleteRecursively(new File(resolvedWorkDir));
        }
        this.active = false;
        log.info("[LocalSandbox] Session {} stopped.", sessionId);
        return true;
    }

    // ========================================================================
    //  Execution
    // ========================================================================

    @Override
    public ExecutionResult execute(String code) {
        return execute(code, null, null);
    }

    @Override
    public ExecutionResult execute(String code, StreamCallback callback) {
        return execute(code, null, callback);
    }

    @Override
    public ExecutionResult execute(String code, byte[] stdin, StreamCallback callback) {
        if (!active || resolvedWorkDir == null) {
            return ExecutionResult.error("Session not started", -1, 0, config.language(), List.of());
        }
        if (code == null || code.isBlank()) {
            return ExecutionResult.error("No code to execute", -1, 0, config.language(), List.of());
        }

        updateLastAccessed();

        // Security gate — hard reject for local runtime.
        List<String> warnings = SecurityUtils.validateCode(code, config.language());
        if (!warnings.isEmpty()) {
            String msg = "Code security check failed: " + String.join("; ", warnings);
            log.warn("[LocalSandbox] Session {} rejected dangerous code: {}", sessionId, msg);
            return ExecutionResult.error(msg, -1, 0, config.language(), warnings);
        }

        boolean hasStdin = stdin != null && stdin.length > 0;
        File codeFile = null;
        File stdinFile = null;
        try {
            // Write code to a temp file in the working directory.
            String filename = sessionId.replaceAll("[^a-zA-Z0-9_-]", "_") + "_"
                    + System.currentTimeMillis() + SandboxConfig.getFileExtension(config.language());
            codeFile = new File(resolvedWorkDir, filename);
            Files.write(codeFile.toPath(), code.getBytes(StandardCharsets.UTF_8));

            // Build the execution command.
            String execCommand = SandboxConfig.getCommandByLanguage(config.language(), filename);
            List<String> command = List.of("sh", "-c", execCommand);

            // Start process with working directory + environment.
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(resolvedWorkDir));
            pb.environment().put("PYTHONDONTWRITEBYTECODE", "1");
            if (config.language().startsWith("python")) {
                pb.environment().put("PYTHONPATH",
                        new File(resolvedWorkDir, ".packages").getAbsolutePath());
            }
            for (Map.Entry<String, String> e : config.environmentVars().entrySet()) {
                pb.environment().put(e.getKey(), e.getValue());
            }

            // Pipe stdin bytes into the process via a temp file redirect; the file
            // hits EOF naturally so sys.stdin.read() returns without deadlock.
            if (hasStdin) {
                stdinFile = File.createTempFile("sandbox_stdin_", ".bin");
                Files.write(stdinFile.toPath(), stdin);
                pb.redirectInput(ProcessBuilder.Redirect.from(stdinFile));
            }

            long startTime = System.currentTimeMillis();
            Process process = pb.start();
            long pid = process.pid();
            synchronized (processPool) {
                processPool.add(pid);
            }

            // Drain stdout and stderr concurrently.
            StringBuilder stdoutBuf = new StringBuilder();
            StringBuilder stderrBuf = new StringBuilder();
            Thread stdoutThread = drainStream(process.getInputStream(), stdoutBuf, callback, false);
            Thread stderrThread = drainStream(process.getErrorStream(), stderrBuf, callback, true);

            long timeoutSec = config.timeoutSeconds() > 0 ? config.timeoutSeconds() : SandboxConfig.MAX_EXECUTION_TIME;
            boolean completed;
            try {
                completed = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ProcessManager.killProcessTree(process);
                joinQuietly(stdoutThread, stderrThread);
                removeFromPool(pid);
                return ExecutionResult.error("Execution interrupted", -1,
                        System.currentTimeMillis() - startTime, config.language(), warnings);
            }

            long durationMs = System.currentTimeMillis() - startTime;
            if (!completed) {
                ProcessManager.killProcessTree(process);
                joinQuietly(stdoutThread, stderrThread);
                removeFromPool(pid);
                return ExecutionResult.timeout(durationMs / 1000, config.language(),
                        "Execution timed out after " + timeoutSec + "s");
            }

            joinQuietly(stdoutThread, stderrThread);
            removeFromPool(pid);

            int exitCode = process.exitValue();
            String stdout = stdoutBuf.toString();
            String stderr = stderrBuf.toString();

            if (exitCode == 0) {
                return ExecutionResult.success(stdout, stderr, exitCode, durationMs,
                        config.language(), List.of(), warnings);
            } else {
                return ExecutionResult.error(stderr, exitCode, durationMs,
                        config.language(), warnings);
            }
        } catch (Exception e) {
            log.error("[LocalSandbox] execute() failed for session {}", sessionId, e);
            return ExecutionResult.error("Execution error: " + e.getMessage(), -1, 0,
                    config.language(), List.of());
        } finally {
            if (codeFile != null) {
                deleteQuietly(codeFile);
            }
            if (stdinFile != null) {
                deleteQuietly(stdinFile);
            }
        }
    }

    // ========================================================================
    //  Dependencies
    // ========================================================================

    @Override
    public ExecutionResult installDependencies(List<String> dependencies) {
        if (!active) {
            return ExecutionResult.error("Session not started", -1, 0, config.language(), List.of());
        }
        if (dependencies == null || dependencies.isEmpty()) {
            return ExecutionResult.success("No dependencies to install", "", 0, 0,
                    config.language(), List.of(), List.of());
        }
        updateLastAccessed();

        String lang = config.language();
        try {
            if (lang.startsWith("python")) {
                return installPip(dependencies);
            } else if (lang.startsWith("javascript")) {
                return installNpm(dependencies);
            } else {
                return ExecutionResult.error(
                        "Dependency installation not supported for language: " + lang, 1,
                        0, lang, List.of());
            }
        } catch (Exception e) {
            return ExecutionResult.error("Dependency install error: " + e.getMessage(), -1,
                    0, lang, List.of());
        }
    }

    private ExecutionResult installPip(List<String> dependencies) throws Exception {
        String depsJoined = String.join(" ", dependencies);
        String packagesDir = new File(resolvedWorkDir, ".packages").getAbsolutePath();
        String cmd = "pip install --no-input --disable-pip-version-check --target "
                + packagesDir + " " + depsJoined;

        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.directory(new File(resolvedWorkDir));
        Process process = pb.start();
        boolean done = process.waitFor(SandboxConfig.MAX_DEPENDENCY_INSTALL_TIME, TimeUnit.SECONDS);
        long durationMs = 0;
        if (!done) {
            ProcessManager.killProcessTree(process);
            return ExecutionResult.error("pip install timed out", -1,
                    SandboxConfig.MAX_DEPENDENCY_INSTALL_TIME * 1000L, config.language(), List.of());
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.exitValue();
        if (exitCode == 0) {
            return ExecutionResult.success("installed: " + depsJoined, stderr, exitCode, 0,
                    config.language(), List.of(), List.of());
        }
        return ExecutionResult.error(stderr, exitCode, 0, config.language(), List.of());
    }

    private ExecutionResult installNpm(List<String> dependencies) throws Exception {
        // npm init -y (idempotent)
        Process init = new ProcessBuilder("sh", "-c", "npm init -y")
                .directory(new File(resolvedWorkDir)).start();
        init.waitFor(15, TimeUnit.SECONDS);

        String depsJoined = String.join(" ", dependencies);
        Process process = new ProcessBuilder("sh", "-c", "npm install " + depsJoined)
                .directory(new File(resolvedWorkDir)).start();
        boolean done = process.waitFor(SandboxConfig.MAX_DEPENDENCY_INSTALL_TIME, TimeUnit.SECONDS);
        if (!done) {
            ProcessManager.killProcessTree(process);
            return ExecutionResult.error("npm install timed out", -1,
                    SandboxConfig.MAX_DEPENDENCY_INSTALL_TIME * 1000L, config.language(), List.of());
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.exitValue();
        if (exitCode == 0) {
            return ExecutionResult.success("installed: " + depsJoined, stderr, exitCode, 0,
                    config.language(), List.of(), List.of());
        }
        return ExecutionResult.error(stderr, exitCode, 0, config.language(), List.of());
    }

    // ========================================================================
    //  File retrieval
    // ========================================================================

    @Override
    public DisplayResult getFileContent(String filename) {
        if (!active || resolvedWorkDir == null || filename == null || filename.isBlank()) {
            return null;
        }
        updateLastAccessed();
        File file = new File(resolvedWorkDir, filename);
        if (!file.exists() || !file.isFile()) {
            return DisplayResult.error("File not found: " + filename, -1);
        }
        try {
            byte[] content = Files.readAllBytes(file.toPath());
            if (content.length > SandboxConfig.MAX_FILE_SIZE) {
                return DisplayResult.error("File too large: " + content.length + " bytes", -1);
            }
            String base64 = Base64.getEncoder().encodeToString(content);
            return DisplayResult.of("success", base64, "", 0, 0, List.of(filename));
        } catch (IOException e) {
            return DisplayResult.error("Failed to read file: " + e.getMessage(), -1);
        }
    }

    // ========================================================================
    //  Status
    // ========================================================================

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        if (!active) {
            status.put("status", "stopped");
            return status;
        }
        status.put("status", "running");
        status.put("workDir", resolvedWorkDir);
        status.put("language", config.language());
        status.put("createdAt", createdAt);
        status.put("lastAccessed", lastAccessed);
        status.put("processCount", processPool.size());
        return status;
    }

    // ========================================================================
    //  Metadata accessors
    // ========================================================================

    @Override public String sessionId() { return sessionId; }
    @Override public SessionConfig config() { return config; }
    @Override public long createdAt() { return createdAt; }
    @Override public long lastAccessed() { return lastAccessed; }
    @Override public boolean isActive() { return active; }
    @Override public void updateLastAccessed() { this.lastAccessed = System.currentTimeMillis(); }

    // ========================================================================
    //  Helpers
    // ========================================================================

    private void removeFromPool(long pid) {
        synchronized (processPool) {
            processPool.removeIf(p -> p == pid);
        }
    }

    private Thread drainStream(java.io.InputStream stream, StringBuilder buffer,
                               StreamCallback callback, boolean isError) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    buffer.append(line).append('\n');
                    if (callback != null) {
                        try {
                            callback.onLine(line, isError);
                        } catch (Exception e) {
                            log.warn("[LocalSandbox] StreamCallback error: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.debug("[LocalSandbox] Stream drain ended: {}", e.getMessage());
            }
        }, "local-sandbox-drain-" + (isError ? "err" : "out") + "-" + sessionId);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread... threads) {
        for (Thread t : threads) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            f.deleteOnExit();
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        if (!f.delete()) {
            f.deleteOnExit();
        }
    }
}
