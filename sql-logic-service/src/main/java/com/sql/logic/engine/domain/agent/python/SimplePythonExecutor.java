package com.sql.logic.engine.domain.agent.python;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Simple Python Sandbox Executor (Phase 4).
 * <p>
 * Runs a generated Python script under a controlled environment. The script must read
 * JSON from {@code sys.stdin} and print a JSON object to stdout (see python-generator.st).
 * <p>
 * Execution strategy (in priority order):
 * <ol>
 *   <li><b>Docker sandbox</b> (preferred, matches the reference project): a hardened
 *       {@code continuumio/anaconda3} container with {@code --network none}, cpu/memory/pids
 *       limits and {@code no-new-privileges}. Guarantees consistent deps (pandas/numpy)
 *       and isolation from the host.</li>
 *   <li><b>Local interpreter fallback</b> (when Docker is unavailable AND the env flag
 *       {@code AGENT_PYTHON_FALLBACK_LOCAL=true}, default true for developer convenience):
 *       runs the host {@code python} with the same stdin contract and a timeout. Useful on
 *       machines without Docker; weaker isolation but unblocks local development.</li>
 *   <li>If neither is available/applicable, a clear error result is returned (never silent).</li>
 * </ol>
 * <p>
 * Configurable via environment variables:
 * <ul>
 *   <li>{@code AGENT_PYTHON_DOCKER_IMAGE} — override the sandbox image (default continuumio/anaconda3).</li>
 *   <li>{@code AGENT_PYTHON_MEMORY_LIMIT} — Docker memory limit (default 512m).</li>
 *   <li>{@code AGENT_PYTHON_LOCAL_BIN} — override local interpreter (default "python").</li>
 *   <li>{@code AGENT_PYTHON_FALLBACK_LOCAL} — enable local fallback (default true).</li>
 * </ul>
 */
@Component
public class SimplePythonExecutor {

    private static final Logger log = LoggerFactory.getLogger(SimplePythonExecutor.class);

    private static final String DEFAULT_DOCKER_IMAGE = "continuumio/anaconda3:latest";
    private static final String DEFAULT_MEMORY_LIMIT = "512m";
    private static final String DEFAULT_LOCAL_BIN = "python";
    private static final long DEFAULT_TIMEOUT_SEC = 60;

    /**
     * Execute a Python script with the given JSON input piped to stdin.
     *
     * @param pythonCode the full script source
     * @param inputJson  the JSON string fed to the script's sys.stdin
     * @param timeoutSec overall execution timeout
     * @return the structured execution result
     */
    public PythonExecutionResult execute(String pythonCode, String inputJson, long timeoutSec) {
        if (timeoutSec <= 0) {
            timeoutSec = DEFAULT_TIMEOUT_SEC;
        }
        File workDir = null;
        try {
            workDir = Files.createTempDirectory("ai_python_exec_").toFile();
            File scriptFile = new File(workDir, "script.py");
            File dataFile = new File(workDir, "input.json");
            Files.write(scriptFile.toPath(), (pythonCode == null ? "" : pythonCode).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.write(dataFile.toPath(), (inputJson == null ? "" : inputJson).getBytes(java.nio.charset.StandardCharsets.UTF_8));

            if (isDockerAvailable()) {
                return runInDocker(scriptFile, dataFile, workDir, timeoutSec);
            }

            if (isLocalFallbackEnabled()) {
                log.warn("[SimplePythonExecutor] Docker unavailable — falling back to local interpreter '{}'.", localBin());
                return runLocally(scriptFile, dataFile, timeoutSec);
            }

            return PythonExecutionResult.fail("", "Python sandbox unavailable: Docker not found and AGENT_PYTHON_FALLBACK_LOCAL is disabled.");
        } catch (Exception e) {
            log.error("[SimplePythonExecutor] Execution failed", e);
            return PythonExecutionResult.fail("", e.getMessage() == null ? "Unknown error" : e.getMessage());
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    /** Convenience overload with the default timeout. */
    public PythonExecutionResult execute(String pythonCode, String inputJson) {
        return execute(pythonCode, inputJson, DEFAULT_TIMEOUT_SEC);
    }

    // ------------------------------------------------------------------
    // Docker sandbox
    // ------------------------------------------------------------------

    private PythonExecutionResult runInDocker(File scriptFile, File dataFile, File workDir, long timeoutSec) {
        String image = envOrDefault("AGENT_PYTHON_DOCKER_IMAGE", DEFAULT_DOCKER_IMAGE);
        String memoryLimit = envOrDefault("AGENT_PYTHON_MEMORY_LIMIT", DEFAULT_MEMORY_LIMIT);

        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("-i");
        command.add("--network");
        command.add("none");
        command.add("--cpus");
        command.add("1");
        command.add("--memory");
        command.add(memoryLimit);
        command.add("--pids-limit");
        command.add("128");
        command.add("--security-opt");
        command.add("no-new-privileges");
        command.add("-v");
        command.add(workDir.getAbsolutePath() + ":/work:ro");
        command.add("-w");
        command.add("/work");
        command.add(image);
        command.add("python");
        command.add("/work/script.py");

        return runProcess(command, dataFile, timeoutSec, "docker " + image);
    }

    // ------------------------------------------------------------------
    // Local interpreter fallback
    // ------------------------------------------------------------------

    private PythonExecutionResult runLocally(File scriptFile, File dataFile, long timeoutSec) {
        String bin = localBin();
        return runProcess(List.of(bin, scriptFile.getAbsolutePath()), dataFile, timeoutSec, "local " + bin);
    }

    // ------------------------------------------------------------------
    // Process runner
    // ------------------------------------------------------------------

    private PythonExecutionResult runProcess(List<String> command, File inputFile, long timeoutSec, String label) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectInput(inputFile)
                    .start();
        } catch (Exception e) {
            return PythonExecutionResult.fail("", "Failed to start process (" + label + "): " + e.getMessage());
        }

        boolean completed;
        try {
            completed = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return PythonExecutionResult.fail("", "Execution interrupted (" + label + ").");
        }
        if (!completed) {
            process.destroyForcibly();
            return PythonExecutionResult.fail("", "Execution timed out after " + timeoutSec + " seconds (" + label + ").");
        }

        try {
            String stdout = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            boolean ok = process.exitValue() == 0;
            return ok
                    ? PythonExecutionResult.ok(stdout, stderr)
                    : PythonExecutionResult.fail(stdout, stderr + (stderr.isEmpty() ? "" : "\n") + "(exit " + process.exitValue() + ", " + label + ")");
        } catch (Exception e) {
            return PythonExecutionResult.fail("", "Failed to read process output: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "--version").start();
            return p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isLocalFallbackEnabled() {
        return !"false".equalsIgnoreCase(System.getenv().getOrDefault("AGENT_PYTHON_FALLBACK_LOCAL", "true"));
    }

    private String localBin() {
        return envOrDefault("AGENT_PYTHON_LOCAL_BIN", DEFAULT_LOCAL_BIN);
    }

    private String envOrDefault(String key, String dflt) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? dflt : v.trim();
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        // best-effort cleanup
        if (!f.delete()) {
            log.debug("[SimplePythonExecutor] Could not delete temp file {}", f.getAbsolutePath());
        }
    }
}