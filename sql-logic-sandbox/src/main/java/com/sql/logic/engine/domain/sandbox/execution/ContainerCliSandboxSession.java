package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxConfig;
import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import com.sql.logic.engine.domain.sandbox.display.DisplayResult;
import com.sql.logic.engine.domain.sandbox.util.PathUtils;

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
 * Stateful container-CLI sandbox session.
 *
 * <p>This is a <b>generic CLI-based session</b> that works with any OCI-compatible
 * container runtime (Docker, Podman, Nerdctl). The binary name ({@code docker},
 * {@code podman}, {@code nerdctl}) is injected via the constructor, making the same
 * implementation reusable across all three runtimes.
 *
 * <p>Each session is backed by a <b>long-lived container</b> ({@code <cli> run -d ...
 * tail -f /dev/null}) that stays alive across multiple {@code execute()} calls.
 *
 * <p>Implementation uses the <b>container CLI</b> via {@link ProcessBuilder},
 * The flow for each execution:
 * <ol>
 *   <li>Write code to a local temp file.</li>
 *   <li>{@code <cli> cp} the file into the container's working directory.</li>
 *   <li>{@code <cli> exec [-i] sh -c "<language-command>"} — stdout is streamed
 *       line-by-line via {@link StreamCallback}. {@code -i} is added when stdin
 *       bytes are present so the piped bytes reach the script's {@code sys.stdin}.</li>
 *   <li>Collect exit code, stdout, stderr, and timing into {@link ExecutionResult}.</li>
 * </ol>
 *
 * <p><b>Security hardening</b>: 
 * {@code --network none --cap-drop ALL --read-only --tmpfs /tmp --tmpfs
 * /workspace --pids-limit 128 --security-opt no-new-privileges --memory --cpus}.
 * The read-only root FS is paired with tmpfs-mounted {@code /workspace} so that
 * {@code pip install --target /workspace/.packages} works.
 */
public class ContainerCliSandboxSession implements SandboxSession {

    private static final Logger log = LoggerFactory.getLogger(ContainerCliSandboxSession.class);

    private final String sessionId;
    private volatile SessionConfig config;
    private final SandboxProperties properties;
    private final String containerName;
    private final String cliBin;

    /** Dynamic environment variable overrides applied via applyConfig(). */
    private volatile Map<String, String> dynamicEnvVars = Map.of();

    private volatile boolean active = false;
    private final long createdAt = System.currentTimeMillis();
    private volatile long lastAccessed = createdAt;

    /**
     * @param sessionId   unique session identifier
     * @param config      session configuration
     * @param cliBinary   the container CLI binary name (e.g. "docker", "podman", "nerdctl")
     * @param properties  sandbox properties (for image/memory/cpu resolution)
     */
    public ContainerCliSandboxSession(String sessionId, SessionConfig config,
                                      String cliBinary, SandboxProperties properties) {
        this.sessionId = sessionId;
        this.config = config;
        this.properties = properties;
        this.containerName = "sandbox_" + sanitizeForContainerName(sessionId);
        this.cliBin = cliBinary;
    }

    // ========================================================================
    //  Lifecycle
    // ========================================================================

    @Override
    public boolean start() {
        List<String> cmd = buildRunCommand();
        log.info("[ContainerSandbox] Starting container: {} (cli={}, image={})",
                containerName, cliBin, properties.effectiveImage(config.language()));
        log.debug("[ContainerSandbox] Full run command: {}", String.join(" ", cmd));

        CommandResult result = runCommand(cmd, 60);
        if (!result.success()) {
            log.error("[ContainerSandbox] Failed to start container {}: exit={} stderr={}",
                    containerName, result.exitCode, result.stderr);
            return false;
        }

        this.active = true;
        this.lastAccessed = System.currentTimeMillis();

        // Set up the workspace package directory for Python (pip --target).
        if (config.language().startsWith("python")) {
            runCommand(List.of(cliBin, "exec", containerName,
                    "mkdir", "-p", config.workingDir() + "/.packages"), 10);
        }
        log.info("[ContainerSandbox] Container {} started successfully.", containerName);
        return true;
    }

    @Override
    public boolean stop() {
        if (!active) {
            return true;
        }
        boolean ok = true;
        // Stop (graceful) then remove (force).
        CommandResult stopResult = runCommand(List.of(cliBin, "stop", "-t", "5", containerName), 15);
        if (!stopResult.success()) {
            log.warn("[ContainerSandbox] {} stop {} failed (exit={}): {}",
                    cliBin, containerName, stopResult.exitCode, stopResult.stderr);
            ok = false;
        }
        CommandResult rmResult = runCommand(List.of(cliBin, "rm", "-f", containerName), 10);
        if (!rmResult.success()) {
            log.warn("[ContainerSandbox] {} rm {} failed (exit={}): {}",
                    cliBin, containerName, rmResult.exitCode, rmResult.stderr);
            ok = false;
        }
        this.active = false;
        log.info("[ContainerSandbox] Container {} stopped and removed (ok={}).", containerName, ok);
        return ok;
    }

    // ========================================================================
    //  Execution
    // ========================================================================

    @Override
    public ExecutionResult execute(String code) {
        return execute(code, null);
    }

    @Override
    public ExecutionResult execute(String code, StreamCallback callback) {
        return execute(code, null, callback);
    }

    @Override
    public ExecutionResult execute(String code, byte[] stdin, StreamCallback callback) {
        if (!active) {
            return ExecutionResult.error("Container not started", -1, 0, config.language(), List.of());
        }

        // VNC/GUI short-circuit: the container is already running /startup.sh which
        // launches the VNC server. execute() returns the guiUrl immediately instead
        // of running code — aligns with DB-GPT's VNC execute() path.
        if (isVncLanguage()) {
            long startTime = System.currentTimeMillis();
            String guiUrl = getVncInfo();
            long durationMs = System.currentTimeMillis() - startTime;
            if (guiUrl == null) {
                return ExecutionResult.error(
                        "VNC container started but guiUrl could not be resolved (inspect failed)",
                        -1, durationMs, config.language(), List.of());
            }
            log.info("[ContainerSandbox] VNC session {} accessible at {}", sessionId, guiUrl);
            return ExecutionResult.vnc(guiUrl, durationMs, config.language());
        }

        if (code == null || code.isBlank()) {
            return ExecutionResult.error("No code to execute", -1, 0, config.language(), List.of());
        }

        boolean hasStdin = stdin != null && stdin.length > 0;
        updateLastAccessed();
        File tempFile = null;
        File stdinFile = null;
        try {
            // 1. Write code to a local temp file.
            String filename = generateFilename();
            tempFile = writeCodeFile(code, filename);

            // 2. Inject into the container working directory.
            String remotePath = config.workingDir() + "/" + filename;
            CommandResult injectResult = runCommand(
                    List.of(cliBin, "exec", "-i", containerName, "sh", "-c", "cat > " + remotePath),
                    15, tempFile);
            if (!injectResult.success()) {
                return ExecutionResult.error(
                        "Failed to inject code into container: " + injectResult.stderr,
                        injectResult.exitCode, 0, config.language(), List.of());
            }

            // 3. exec [-i] — run the language command with streaming stdout.
            //    -i keeps stdin open so the piped bytes reach the script's sys.stdin.
            String execCommand = SandboxConfig.getCommandByLanguage(config.language(), filename);
            List<String> execArgs = buildExecCommand(execCommand, hasStdin);

            // 4. Stage stdin bytes in a temp file so ProcessBuilder can redirect it
            //    into the process; the file hits EOF naturally (no deadlock).
            if (hasStdin) {
                stdinFile = File.createTempFile("sandbox_stdin_", ".bin");
                Files.write(stdinFile.toPath(), stdin);
            }

            long startTime = System.currentTimeMillis();
            return runStreamingExec(execArgs, execCommand, callback, startTime, stdinFile);
        } catch (Exception e) {
            log.error("[ContainerSandbox] execute() failed for session {}", sessionId, e);
            return ExecutionResult.error("Execution error: " + e.getMessage(), -1, 0, config.language(), List.of());
        } finally {
            if (tempFile != null) {
                deleteQuietly(tempFile);
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
            return ExecutionResult.error("Container not started", -1, 0, config.language(), List.of());
        }
        if (dependencies == null || dependencies.isEmpty()) {
            return ExecutionResult.success("No dependencies to install", "", 0, 0,
                    config.language(), List.of(), List.of());
        }
        updateLastAccessed();

        String lang = config.language();
        try {
            if (lang.startsWith("python")) {
                return installPipDependencies(dependencies);
            } else if (lang.startsWith("javascript")) {
                return installNpmDependencies(dependencies);
            } else {
                return ExecutionResult.error(
                        "Dependency installation not supported for language: " + lang, 1,
                        0, lang, List.of());
            }
        } catch (Exception e) {
            log.error("[ContainerSandbox] installDependencies() failed for session {}", sessionId, e);
            return ExecutionResult.error("Dependency install error: " + e.getMessage(), -1,
                    0, lang, List.of());
        }
    }

    private ExecutionResult installPipDependencies(List<String> dependencies) {
        // pip install --target /workspace/.packages so packages persist in the
        // tmpfs-mounted workspace (root FS is read-only). PYTHONPATH is preset
        // on the container so subsequent execute() calls find them.
        String depsJoined = String.join(" ", dependencies);
        String pipCmd = "pip install --no-input --disable-pip-version-check --target "
                + config.workingDir() + "/.packages " + depsJoined;

        long startTime = System.currentTimeMillis();
        CommandResult result = runCommand(
                List.of(cliBin, "exec", containerName, "sh", "-c", pipCmd),
                SandboxConfig.MAX_DEPENDENCY_INSTALL_TIME);
        long duration = System.currentTimeMillis() - startTime;

        List<String> files = List.of();
        if (result.success()) {
            String output = "installed: " + depsJoined;
            // Pass pip install stdout as logs so the frontend can show the install
            // progress in a collapsible section (aligns with DB-GPT DisplayResult.logs).
            List<String> logs = splitLines(result.stdout);
            return ExecutionResult.success(output, result.stderr, result.exitCode, duration,
                    config.language(), files, logs, List.of());
        } else {
            return ExecutionResult.error(result.stderr, result.exitCode, duration,
                    config.language(), List.of());
        }
    }

    private ExecutionResult installNpmDependencies(List<String> dependencies) {
        // npm init -y (idempotent) then npm install <deps> in /workspace.
        runCommand(List.of(cliBin, "exec", "-w", config.workingDir(), containerName,
                "sh", "-c", "npm init -y"), 15);

        String depsJoined = String.join(" ", dependencies);
        String npmCmd = "npm install " + depsJoined;

        long startTime = System.currentTimeMillis();
        CommandResult result = runCommand(
                List.of(cliBin, "exec", "-w", config.workingDir(), containerName, "sh", "-c", npmCmd),
                SandboxConfig.MAX_DEPENDENCY_INSTALL_TIME);
        long duration = System.currentTimeMillis() - startTime;

        if (result.success()) {
            List<String> logs = splitLines(result.stdout);
            return ExecutionResult.success("installed: " + depsJoined, result.stderr,
                    result.exitCode, duration, config.language(), List.of(), logs, List.of());
        } else {
            return ExecutionResult.error(result.stderr, result.exitCode, duration,
                    config.language(), List.of());
        }
    }

    // ========================================================================
    //  File retrieval
    // ========================================================================

    @Override
    public DisplayResult getFileContent(String filename) {
        if (!active || filename == null || filename.isBlank()) {
            return null;
        }
        // Path traversal prevention — reject filenames that escape the working dir.
        String safeFilename;
        try {
            safeFilename = PathUtils.ensureSafeFilename(filename);
        } catch (IllegalArgumentException e) {
            log.warn("[ContainerSandbox] Rejected unsafe filename: {}", filename);
            return DisplayResult.error(e.getMessage(), -1);
        }
        updateLastAccessed();
        File tempFile = null;
        try {
            String remotePath = config.workingDir() + "/" + safeFilename;
            tempFile = Files.createTempFile("sandbox_fetch_", "_file").toFile();

            // `docker cp` cannot read from this container either: /workspace is a
            // tmpfs mount that only exists inside the container's mount namespace,
            // so the host-side `docker cp container:/workspace/...` cannot see it
            // (and the overlay rootfs is --read-only). Reading via `docker exec cat`
            // runs inside the container and captures the tmpfs bytes directly.
            CommandResult readResult = runCommandToFile(
                    List.of(cliBin, "exec", containerName, "sh", "-c", "cat " + remotePath),
                    tempFile, 15);
            if (!readResult.success() || tempFile.length() == 0) {
                return DisplayResult.error("File not found: " + filename, -1);
            }

            byte[] content = Files.readAllBytes(tempFile.toPath());
            if (content.length > SandboxConfig.MAX_FILE_SIZE) {
                return DisplayResult.error("File too large: " + content.length + " bytes (max "
                        + SandboxConfig.MAX_FILE_SIZE + ")", -1);
            }
            String base64 = Base64.getEncoder().encodeToString(content);
            return DisplayResult.of("success", base64, "", 0, 0, List.of(filename));
        } catch (Exception e) {
            log.error("[ContainerSandbox] getFileContent() failed for {}: {}", filename, e.getMessage());
            return DisplayResult.error("Failed to fetch file: " + e.getMessage(), -1);
        } finally {
            if (tempFile != null) {
                deleteQuietly(tempFile);
            }
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
        CommandResult result = runCommand(
                List.of(cliBin, "inspect", "--format", "{{.State.Status}}", containerName), 5);
        status.put("status", result.success() ? result.stdout.trim() : "unknown");
        status.put("containerName", containerName);
        status.put("cli", cliBin);
        status.put("language", config.language());
        status.put("createdAt", createdAt);
        status.put("lastAccessed", lastAccessed);
        return status;
    }

    // ========================================================================
    //  Dynamic configuration + resource stats
    // ========================================================================

    @Override
    public void applyConfig(Map<String, String> envOverrides, Integer timeoutOverride) {
        if (envOverrides != null && !envOverrides.isEmpty()) {
            // Merge into a new map (volatile swap for thread safety).
            Map<String, String> merged = new LinkedHashMap<>(dynamicEnvVars);
            merged.putAll(envOverrides);
            this.dynamicEnvVars = Map.copyOf(merged);
            log.debug("[ContainerSandbox] Applied {} env overrides to session {}", envOverrides.size(), sessionId);
        }
        if (timeoutOverride != null && timeoutOverride > 0) {
            // SessionConfig is a record (immutable) — create a new instance with the
            // updated timeout. Working-dir changes are NOT applied at runtime (the
            // container is already started with the original working dir).
            SessionConfig current = this.config;
            this.config = new SessionConfig(
                    current.language(), timeoutOverride, current.maxMemoryBytes(),
                    current.maxCpus(), current.workingDir(), current.environmentVars(),
                    current.networkDisabled());
            log.debug("[ContainerSandbox] Applied timeout override {}s to session {}", timeoutOverride, sessionId);
        }
    }

    @Override
    public Map<String, Object> collectStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        if (!active) {
            return stats;
        }
        try {
            // docker stats --no-stream --format "{{json .}}" — returns one JSON line.
            CommandResult result = runCommand(
                    List.of(cliBin, "stats", "--no-stream",
                            "--format", "{{json .}}", containerName), 5);
            if (result.success() && !result.stdout.isBlank()) {
                stats.put("raw", result.stdout.trim());
                // Best-effort parsing of common fields. The JSON looks like:
                // {"Name":"...","CPUPerc":"0.12%","MemUsage":"12.34MiB / 256MiB",...}
                String json = result.stdout.trim();
                stats.put("cpuUsage", extractJsonField(json, "CPUPerc"));
                stats.put("memoryUsage", extractJsonField(json, "MemUsage"));
                stats.put("netIO", extractJsonField(json, "NetIO"));
                stats.put("blockIO", extractJsonField(json, "BlockIO"));
                stats.put("pids", extractJsonField(json, "PIDs"));
            }
        } catch (Exception e) {
            log.debug("[ContainerSandbox] collectStats() failed: {}", e.getMessage());
        }
        return stats;
    }

    /** Extract a string field value from a flat JSON object (best-effort regex). */
    private static String extractJsonField(String json, String field) {
        if (json == null || field == null) {
            return null;
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"" + field + "\"\\s*:\\s*\"([^\"]*)\"");
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : null;
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
    //  Command builders
    // ========================================================================

    /**
     * Build the {@code <cli> run -d} command with all security hardening flags.
     * Container stays alive via {@code tail -f /dev/null} (normal) or
     * {@code /startup.sh} (VNC/GUI languages).
     *
     * <p>VNC languages ({@code *-vnc}) get a different network policy: port
     * mappings ({@code -p 5900:5900 -p 6080:6080}) replace {@code --network none}
     * so the noVNC web client can reach the VNC server. The keep-alive command
     * becomes {@code /startup.sh} which launches the VNC + noVNC server.
     */
    private List<String> buildRunCommand() {
        List<String> cmd = new ArrayList<>();
        cmd.add(cliBin);
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(containerName);

        boolean isVnc = config.language() != null && config.language().endsWith("-vnc");

        // --- Security hardening ---
        // VNC containers need port mappings, and network-enabled sessions
        // (networkDisabled=false, e.g. for dependency installation) opt out of
        // --network none explicitly. Everything else runs fully isolated.
        if (isVnc) {
            // Publish VNC (5900) and noVNC web (6080) ports. Use "0" for dynamic
            // host port binding when no fixed port is configured; the actual port
            // is resolved later via <cli> inspect (see getVncInfo()).
            String hostPort = properties.getVncHostPort();
            if (hostPort == null || hostPort.isBlank()) {
                cmd.add("-p"); cmd.add("5900:5900");
                cmd.add("-p"); cmd.add("6080:6080");
            } else {
                cmd.add("-p"); cmd.add(hostPort + ":6080");
                cmd.add("-p"); cmd.add("5900:5900");
            }
        } else if (config.networkDisabled()) {
            cmd.add("--network"); cmd.add("none");
        }
        cmd.add("--cap-drop"); cmd.add("ALL");
        cmd.add("--read-only");
        cmd.add("--tmpfs"); cmd.add("/tmp:size=64m");
        cmd.add("--tmpfs"); cmd.add(config.workingDir() + ":size=128m");
        cmd.add("--pids-limit"); cmd.add(String.valueOf(SandboxConfig.MAX_PROCESSES * 13)); // ~128
        cmd.add("--security-opt"); cmd.add("no-new-privileges");

        // Optional seccomp profile
        String seccomp = properties.getSeccompProfilePath();
        if (seccomp != null && !seccomp.isBlank()) {
            cmd.add("--security-opt"); cmd.add("seccomp=" + seccomp);
        }

        // --- Resource limits ---
        cmd.add("--memory"); cmd.add(properties.getMemoryLimit());
        cmd.add("--cpus"); cmd.add(String.valueOf(properties.getCpus()));

        // --- Environment ---
        cmd.add("-e"); cmd.add("PYTHONDONTWRITEBYTECODE=1");
        if (config.language().startsWith("python")) {
            cmd.add("-e"); cmd.add("PYTHONPATH=" + config.workingDir() + "/.packages");
        }
        for (Map.Entry<String, String> e : config.environmentVars().entrySet()) {
            cmd.add("-e"); cmd.add(e.getKey() + "=" + e.getValue());
        }

        // --- Working directory + image + keep-alive command ---
        cmd.add("-w"); cmd.add(config.workingDir());
        cmd.add(properties.effectiveImage(config.language()));
        if (isVnc) {
            // VNC image's entrypoint script launches the VNC server + noVNC web client.
            cmd.add("/startup.sh");
        } else {
            cmd.add("tail"); cmd.add("-f"); cmd.add("/dev/null");
        }

        return cmd;
    }

    /**
     * Build the {@code <cli> exec} command, optionally with {@code -i} to keep stdin
     * open so piped bytes reach the script's {@code sys.stdin}.
     *
     * @param execCommand the shell command string to run inside the container
     * @param interactive when true, adds {@code -i} so stdin is connected (required for
     *                    the native stdin pipe path that replaces the base64 shim)
     */
    private List<String> buildExecCommand(String execCommand, boolean interactive) {
        List<String> cmd = new ArrayList<>();
        cmd.add(cliBin);
        cmd.add("exec");
        if (interactive) {
            cmd.add("-i");
        }
        cmd.add("-w"); cmd.add(config.workingDir());
        if (config.language().startsWith("python")) {
            cmd.add("-e"); cmd.add("PYTHONPATH=" + config.workingDir() + "/.packages");
            cmd.add("-e"); cmd.add("PYTHONDONTWRITEBYTECODE=1");
        }
        for (Map.Entry<String, String> e : config.environmentVars().entrySet()) {
            cmd.add("-e"); cmd.add(e.getKey() + "=" + e.getValue());
        }
        // Merge dynamic env overrides from applyConfig().
        for (Map.Entry<String, String> e : dynamicEnvVars.entrySet()) {
            cmd.add("-e"); cmd.add(e.getKey() + "=" + e.getValue());
        }
        cmd.add(containerName);
        cmd.add("sh"); cmd.add("-c"); cmd.add(execCommand);
        return cmd;
    }

    // ========================================================================
    //  Process execution helpers
    // ========================================================================

    /**
     * Run a one-shot command (no streaming), waiting up to {@code timeoutSec}.
     * Reads stdout and stderr fully into the returned result.
     */
    private CommandResult runCommand(List<String> command, long timeoutSec) {
        return runCommand(command, timeoutSec, null);
    }

    /**
     * Run a one-shot command whose stdout is redirected directly to {@code outFile}
     * (byte-safe — used for binary file retrieval), waiting up to {@code timeoutSec}.
     * Only stderr is buffered into the returned result.
     */
    private CommandResult runCommandToFile(List<String> command, File outFile, long timeoutSec) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        pb.redirectOutput(ProcessBuilder.Redirect.to(outFile));
        Process process = null;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new CommandResult(-1, "", "Failed to start command: " + e.getMessage(), false);
        }

        StringBuilder stderrBuf = new StringBuilder();
        Thread stderrThread = drainStream(process.getErrorStream(), stderrBuf, null, true);

        boolean completed;
        try {
            completed = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            joinQuietly(stderrThread);
            return new CommandResult(-1, "", "Interrupted", false);
        }

        if (!completed) {
            process.destroyForcibly();
            joinQuietly(stderrThread);
            return new CommandResult(-1, "", "Timed out after " + timeoutSec + "s", true);
        }

        joinQuietly(stderrThread);
        return new CommandResult(process.exitValue(), "", stderrBuf.toString(), false);
    }

    /**
     * Run a one-shot command (no streaming), waiting up to {@code timeoutSec}.
     * Reads stdout and stderr fully into the returned result. When
     * {@code stdinFile} is non-null, its contents are redirected into the
     * process's stdin (the file reaches EOF naturally, so no deadlock).
     */
    private CommandResult runCommand(List<String> command, long timeoutSec, File stdinFile) {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(false);
        if (stdinFile != null) {
            pb.redirectInput(ProcessBuilder.Redirect.from(stdinFile));
        }
        Process process = null;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new CommandResult(-1, "", "Failed to start command: " + e.getMessage(), false);
        }

        StringBuilder stdoutBuf = new StringBuilder();
        StringBuilder stderrBuf = new StringBuilder();
        Thread stdoutThread = drainStream(process.getInputStream(), stdoutBuf, null, false);
        Thread stderrThread = drainStream(process.getErrorStream(), stderrBuf, null, true);

        boolean completed;
        try {
            completed = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            joinQuietly(stdoutThread, stderrThread);
            return new CommandResult(-1, stdoutBuf.toString(), "Interrupted", false);
        }

        if (!completed) {
            process.destroyForcibly();
            joinQuietly(stdoutThread, stderrThread);
            return new CommandResult(-1, stdoutBuf.toString(),
                    "Timed out after " + timeoutSec + "s", true);
        }

        joinQuietly(stdoutThread, stderrThread);
        int exitCode = process.exitValue();
        return new CommandResult(exitCode, stdoutBuf.toString(), stderrBuf.toString(), false);
    }

    /**
     * Run {@code <cli> exec} with line-by-line stdout streaming via the callback.
     * When {@code stdinFile} is non-null, its contents are piped into the process's
     * stdin via {@link ProcessBuilder.Redirect#from(File)} — the file reaches EOF
     * naturally, so the script's {@code sys.stdin.read()} returns and the process
     * exits without deadlock (no need to manually close a stream on a timer).
     *
     * @param stdinFile temp file holding the raw stdin bytes (e.g. inputJson), or null
     */
    private ExecutionResult runStreamingExec(List<String> execArgs, String execCommand,
                                             StreamCallback callback, long startTime,
                                             File stdinFile) {
        log.debug("[ContainerSandbox] exec: {} (cmd: {}, stdin={})",
                containerName, execCommand, stdinFile != null ? stdinFile.length() + "B" : "none");

        ProcessBuilder pb = new ProcessBuilder(execArgs).redirectErrorStream(false);
        if (stdinFile != null) {
            pb.redirectInput(ProcessBuilder.Redirect.from(stdinFile));
        }
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return ExecutionResult.error("Failed to start exec: " + e.getMessage(),
                    -1, System.currentTimeMillis() - startTime, config.language(), List.of());
        }

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
            process.destroyForcibly();
            joinQuietly(stdoutThread, stderrThread);
            return ExecutionResult.error("Execution interrupted", -1,
                    System.currentTimeMillis() - startTime, config.language(), List.of());
        }

        long durationMs = System.currentTimeMillis() - startTime;
        if (!completed) {
            process.destroyForcibly();
            joinQuietly(stdoutThread, stderrThread);
            return ExecutionResult.timeout(durationSec(durationMs), config.language(),
                    "Execution timed out after " + timeoutSec + "s");
        }

        joinQuietly(stdoutThread, stderrThread);
        int exitCode = process.exitValue();
        String stdout = stdoutBuf.toString();
        String stderr = stderrBuf.toString();

        if (exitCode == 0) {
            // Collect produced files (best-effort, non-blocking) — aligns with
            // DB-GPT's os.listdir(workdir) to surface charts/CSVs/images.
            List<String> files = listWorkspaceFiles();
            return ExecutionResult.success(stdout, stderr, exitCode, durationMs,
                    config.language(), files, List.of());
        } else {
            return ExecutionResult.error(stderr, exitCode, durationMs,
                    config.language(), List.of());
        }
    }

    /**
     * Drain a process stream line-by-line into a buffer, optionally invoking the
     * callback for each line. Returns the daemon thread doing the draining.
     */
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
                            log.warn("[ContainerSandbox] StreamCallback error: {}", e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                // Stream closed (process killed) — normal during timeout cleanup.
                log.debug("[ContainerSandbox] Stream drain ended: {}", e.getMessage());
            }
        }, "sandbox-drain-" + (isError ? "err" : "out") + "-" + sessionId);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // ========================================================================
    //  Small helpers
    // ========================================================================

    private String generateFilename() {
        return sanitizeForFilename(sessionId) + "_" + System.currentTimeMillis()
                + SandboxConfig.getFileExtension(config.language());
    }

    private File writeCodeFile(String code, String filename) throws IOException {
        File tempFile = new File(System.getProperty("java.io.tmpdir"), filename);
        Files.write(tempFile.toPath(), code.getBytes(StandardCharsets.UTF_8));
        return tempFile;
    }

    private static long durationSec(long durationMs) {
        return durationMs / 1000;
    }

    /** Split a multi-line string into a list of non-blank lines (for logs passthrough). */
    private static List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return text.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * List files in the container working directory after a successful execution.
     */
    private List<String> listWorkspaceFiles() {
        try {
            CommandResult result = runCommand(
                    List.of(cliBin, "exec", containerName, "sh", "-c",
                            "ls -1 " + config.workingDir() + " 2>/dev/null"),
                    5);
            if (!result.success()) {
                return List.of();
            }
            return splitLines(result.stdout);
        } catch (Exception e) {
            log.debug("[ContainerSandbox] listWorkspaceFiles() failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Whether this session's language is a VNC/GUI variant (ends with {@code -vnc}). */
    private boolean isVncLanguage() {
        return config.language() != null && config.language().endsWith("-vnc");
    }

    /**
     * Resolve the noVNC web URL for this VNC container.
     *
     * <p>Strategy:
     * <ol>
     *   <li>If a fixed {@code sandbox.vnc-host-port} is configured, use it directly.</li>
     *   <li>Otherwise, run {@code <cli> inspect --format '{{json .NetworkSettings.Ports}}'}
     *       and parse the {@code 6080/tcp} host port from the JSON.</li>
     *   <li>If both fail, return null (caller reports an error).</li>
     * </ol>
     *
     * @return the guiUrl (e.g. {@code http://localhost:6080/vnc.html}), or null if unresolved
     */
    private String getVncInfo() {
        // 1. Fixed port override — skip inspect entirely.
        String fixedPort = properties.getVncHostPort();
        if (fixedPort != null && !fixedPort.isBlank()) {
            return "http://localhost:" + fixedPort.trim() + "/vnc.html";
        }

        // 2. Dynamic port — inspect the container's port bindings.
        try {
            CommandResult result = runCommand(
                    List.of(cliBin, "inspect", "--format",
                            "{{json .NetworkSettings.Ports}}", containerName), 5);
            if (!result.success()) {
                log.warn("[ContainerSandbox] VNC inspect failed for {}: {}", sessionId, result.stderr);
                return null;
            }
            String portsJson = result.stdout.trim();
            return parseVncPort(portsJson);
        } catch (Exception e) {
            log.warn("[ContainerSandbox] VNC port resolution failed for {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * Parse the {@code 6080/tcp} host port from a Docker/Podman inspect ports JSON.
     * The JSON looks like: {@code {"6080/tcp":[{"HostIp":"0.0.0.0","HostPort":"6080"}],...}}
     * Uses a lightweight regex to avoid pulling in a Jackson dependency here.
     *
     * <p>Package-private for unit testing.
     */
    static String parseVncPort(String portsJson) {
        if (portsJson == null || portsJson.isBlank() || "null".equals(portsJson)) {
            return null;
        }
        // Match "6080/tcp":[...,"HostPort":"<port>"...] — handles both fixed and
        // dynamic port bindings. The regex is deliberately permissive about the
        // JSON structure between the key and the HostPort value.
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\"6080/tcp\"\\s*:\\s*\\[.*?\"HostPort\"\\s*:\\s*\"(\\d+)\"");
        java.util.regex.Matcher m = p.matcher(portsJson);
        if (m.find()) {
            return "http://localhost:" + m.group(1) + "/vnc.html";
        }
        return null;
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

    private static String sanitizeForContainerName(String s) {
        return s == null ? "session" : s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    private static String sanitizeForFilename(String s) {
        return s == null ? "session" : s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /** Internal result of a one-shot command. */
    private record CommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
        boolean success() {
            return exitCode == 0;
        }
    }
}
