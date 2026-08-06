package com.sql.logic.engine.domain.sandbox.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Environment detection.
 *
 * <p>Detects which container runtimes (Docker / Podman / Nerdctl) are available
 * on the host, and provides basic system info for health checks. Used by
 * {@code RuntimeFactory} to select the best runtime at boot.
 */
public final class EnvironmentDetector {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentDetector.class);

    private EnvironmentDetector() {
    }

    /** Check if the {@code docker} CLI is on PATH and the daemon responds. */
    public static boolean isDockerAvailable() {
        return isRuntimeAvailable("docker");
    }

    /** Check if the {@code podman} CLI is on PATH and the daemon responds. */
    public static boolean isPodmanAvailable() {
        return isRuntimeAvailable("podman");
    }

    /** Check if the {@code nerdctl} CLI is on PATH. */
    public static boolean isNerdctlAvailable() {
        return isCommandAvailable("nerdctl");
    }

    /**
     * Check if a container runtime CLI exists on PATH and responds to
     * {@code <runtime> info}.
     */
    private static boolean isRuntimeAvailable(String runtime) {
        if (!isCommandAvailable(runtime)) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(runtime, "info", "--format", "{{.ServerVersion}}").start();
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            log.debug("[EnvironmentDetector] {} info probe failed: {}", runtime, e.getMessage());
            return false;
        }
    }

    /** Check if a command exists on the system PATH. */
    public static boolean isCommandAvailable(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            File file = new File(dir, command);
            if (file.canExecute()) {
                return true;
            }
            // Windows fallback
            File exe = new File(dir, command + ".exe");
            if (exe.canExecute()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collect basic system info for health checks. Uses only JDK APIs (no psutil
     * equivalent needed in Java).
     */
    public static Map<String, Object> getSystemInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("cpuCount", Runtime.getRuntime().availableProcessors());
        info.put("memoryTotal", Runtime.getRuntime().maxMemory());
        info.put("memoryFree", Runtime.getRuntime().freeMemory());
        return info;
    }
}
