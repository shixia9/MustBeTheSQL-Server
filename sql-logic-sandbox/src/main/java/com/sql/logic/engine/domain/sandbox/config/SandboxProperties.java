package com.sql.logic.engine.domain.sandbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunable sandbox settings bound from {@code sandbox.*} properties.
 *
 * <p>Static defaults (language images, commands, global limits) live in
 * {@link SandboxConfig}; this class holds only the operator-tunable knobs.
 *
 * <p>Security defaults are fail-closed:
 * <ul>
 *   <li>{@link #allowLocalRuntime} defaults to {@code false} — host execution must be
 *       explicitly opted in.</li>
 *   <li>When Docker is unavailable and local is not opted in, the factory rejects
 *       execution (fail-closed). There is no implicit host fallback — the legacy
 *       {@code AGENT_PYTHON_FALLBACK_LOCAL=true} default has been removed.</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "sandbox")
public class SandboxProperties {

    /** Override the Docker image for the default language (else resolved per-language). */
    private String dockerImage = "";

    /** Docker memory limit passed via {@code --memory} (e.g. "256m", "512m"). */
    private String memoryLimit = "256m";

    /** CPU limit passed via {@code --cpus} (e.g. 1.0). */
    private double cpus = 1.0;

    /** Default per-execution timeout in seconds. */
    private int timeout = SandboxConfig.MAX_EXECUTION_TIME;

    /** Default language when a session does not specify one. */
    private String defaultLanguage = "python";

    /**
     * Opt-in to the LocalSandboxRuntime (host-process execution). Default false — fail-closed.
     */
    private boolean allowLocalRuntime = false;

    /** Path to a custom seccomp profile for {@code --security-opt seccomp=}. Empty = none. */
    private String seccompProfilePath = "";

    /** Working directory inside the sandbox container. */
    private String workingDir = SandboxConfig.WORKING_DIR;

    /** Idle threshold (seconds) for {@code cleanupExpiredSessions()}. Default 1 hour. */
    private long sessionIdleTimeout = 3600L;

    /** Docker binary path (empty = auto-detect {@code docker} on PATH). */
    private String dockerBinary = "";

    /** Podman binary path (empty = auto-detect {@code podman} on PATH). */
    private String podmanBinary = "";

    /** Nerdctl binary path (empty = auto-detect {@code nerdctl} on PATH). */
    private String nerdctlBinary = "";

    /**
     * Whether VNC/GUI sandbox support is enabled. When false, {@code python-vnc}
     * session creation will fail-closed (the VNC image is typically not pre-built).
     */
    private boolean vncEnabled = false;

    /** Docker image for VNC/GUI sessions (default: {@code vnc-gui-browser:latest}). */
    private String vncImage = "vnc-gui-browser:latest";

    /**
     * Fixed host port for VNC web access (e.g. "6080"). When empty, the runtime
     * publishes 6080 to a dynamic port and resolves it via {@code <cli> inspect}.
     * Set this to a fixed value to avoid inspect round-trips (useful for rootless
     * Podman where inspect port parsing may differ).
     */
    private String vncHostPort = "";

    // ---- Getters / Setters ----

    public String getDockerImage() { return dockerImage; }
    public void setDockerImage(String dockerImage) { this.dockerImage = dockerImage; }

    public String getMemoryLimit() { return memoryLimit; }
    public void setMemoryLimit(String memoryLimit) { this.memoryLimit = memoryLimit; }

    public double getCpus() { return cpus; }
    public void setCpus(double cpus) { this.cpus = cpus; }

    public int getTimeout() { return timeout; }
    public void setTimeout(int timeout) { this.timeout = timeout; }

    public String getDefaultLanguage() { return defaultLanguage; }
    public void setDefaultLanguage(String defaultLanguage) { this.defaultLanguage = defaultLanguage; }

    public boolean isAllowLocalRuntime() { return allowLocalRuntime; }
    public void setAllowLocalRuntime(boolean allowLocalRuntime) { this.allowLocalRuntime = allowLocalRuntime; }

    public String getSeccompProfilePath() { return seccompProfilePath; }
    public void setSeccompProfilePath(String seccompProfilePath) { this.seccompProfilePath = seccompProfilePath; }

    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

    public long getSessionIdleTimeout() { return sessionIdleTimeout; }
    public void setSessionIdleTimeout(long sessionIdleTimeout) { this.sessionIdleTimeout = sessionIdleTimeout; }

    public String getDockerBinary() { return dockerBinary; }
    public void setDockerBinary(String dockerBinary) { this.dockerBinary = dockerBinary; }

    public String getPodmanBinary() { return podmanBinary; }
    public void setPodmanBinary(String podmanBinary) { this.podmanBinary = podmanBinary; }

    public String getNerdctlBinary() { return nerdctlBinary; }
    public void setNerdctlBinary(String nerdctlBinary) { this.nerdctlBinary = nerdctlBinary; }

    public boolean isVncEnabled() { return vncEnabled; }
    public void setVncEnabled(boolean vncEnabled) { this.vncEnabled = vncEnabled; }

    public String getVncImage() { return vncImage; }
    public void setVncImage(String vncImage) { this.vncImage = vncImage; }

    public String getVncHostPort() { return vncHostPort; }
    public void setVncHostPort(String vncHostPort) { this.vncHostPort = vncHostPort; }

    /** Convenience: resolve the effective docker binary (configured or "docker"). */
    public String effectiveDockerBinary() {
        return (dockerBinary == null || dockerBinary.isBlank()) ? "docker" : dockerBinary.trim();
    }

    /** Convenience: resolve the effective podman binary (configured or "podman"). */
    public String effectivePodmanBinary() {
        return (podmanBinary == null || podmanBinary.isBlank()) ? "podman" : podmanBinary.trim();
    }

    /** Convenience: resolve the effective nerdctl binary (configured or "nerdctl"). */
    public String effectiveNerdctlBinary() {
        return (nerdctlBinary == null || nerdctlBinary.isBlank()) ? "nerdctl" : nerdctlBinary.trim();
    }

    /** Convenience: resolve the image for a language, honouring the override if set. */
    public String effectiveImage(String language) {
        // VNC languages use the dedicated vncImage override (if set), falling back
        // to the LANGUAGE_IMAGES entry for the language.
        if (language != null && language.endsWith("-vnc")) {
            if (vncImage != null && !vncImage.isBlank()) {
                return vncImage.trim();
            }
            return SandboxConfig.getImageByLanguage(language);
        }
        if (dockerImage != null && !dockerImage.isBlank()) {
            return dockerImage.trim();
        }
        return SandboxConfig.getImageByLanguage(language);
    }
}
