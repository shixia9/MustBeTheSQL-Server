package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Automatic sandbox runtime factory.
 *
 * <p>Selects the best available runtime at boot, following priority:
 * <ol>
 *   <li><b>Docker</b> — if the Docker daemon is reachable.</li>
 *   <li><b>Podman</b> — if Docker is unavailable but Podman is present.</li>
 *   <li><b>Nerdctl</b> — if both Docker and Podman are unavailable but nerdctl
 *       is present.</li>
 *   <li><b>Local</b> (opt-in) — if {@code sandbox.allow-local-runtime=true} and
 *       no container runtime is available.</li>
 *   <li><b>Fail-closed</b> — if no runtime is available and local is not opted in,
 *       {@link #getRuntime()} throws. There is no implicit host fallback.</li>
 * </ol>
 *
 * <p>All four runtimes are Spring components (always instantiated); the factory
 * only <em>selects</em> which one to activate based on availability and
 * configuration. Each container runtime probes its CLI binary via
 * {@code isCliAvailable()}.
 */
@Component
public class RuntimeFactory {

    private static final Logger log = LoggerFactory.getLogger(RuntimeFactory.class);

    private final SandboxProperties properties;
    private final DockerSandboxRuntime dockerRuntime;
    private final PodmanSandboxRuntime podmanRuntime;
    private final NerdctlSandboxRuntime nerdctlRuntime;
    private final LocalSandboxRuntime localRuntime;

    private volatile SandboxRuntime selectedRuntime;
    private volatile String selectionReason;

    public RuntimeFactory(SandboxProperties properties,
                          DockerSandboxRuntime dockerRuntime,
                          PodmanSandboxRuntime podmanRuntime,
                          NerdctlSandboxRuntime nerdctlRuntime,
                          LocalSandboxRuntime localRuntime) {
        this.properties = properties;
        this.dockerRuntime = dockerRuntime;
        this.podmanRuntime = podmanRuntime;
        this.nerdctlRuntime = nerdctlRuntime;
        this.localRuntime = localRuntime;
    }

    @PostConstruct
    void selectRuntime() {
        // 1. Try Docker.
        if (dockerRuntime.isCliAvailable()) {
            selectedRuntime = dockerRuntime;
            selectionReason = "Docker daemon available";
            log.info("[RuntimeFactory] Selected DockerSandboxRuntime (Docker daemon available).");
            return;
        }

        // 2. Try Podman.
        if (podmanRuntime.isCliAvailable()) {
            selectedRuntime = podmanRuntime;
            selectionReason = "Docker unavailable, Podman available";
            log.info("[RuntimeFactory] Selected PodmanSandboxRuntime (Docker unavailable, Podman available).");
            return;
        }

        // 3. Try Nerdctl.
        if (nerdctlRuntime.isCliAvailable()) {
            selectedRuntime = nerdctlRuntime;
            selectionReason = "Docker and Podman unavailable, nerdctl available";
            log.info("[RuntimeFactory] Selected NerdctlSandboxRuntime (nerdctl available).");
            return;
        }

        // 4. Fall back to local runtime if explicitly opted in.
        if (properties.isAllowLocalRuntime()) {
            selectedRuntime = localRuntime;
            selectionReason = "No container runtime available, local runtime opted in (allow-local-runtime=true)";
            log.warn("[RuntimeFactory] No container runtime available — falling back to LocalSandboxRuntime "
                    + "(WEAKER ISOLATION). Session: {}", selectionReason);
            return;
        }

        // 5. Fail-closed.
        selectedRuntime = null;
        selectionReason = "No container runtime available (Docker/Podman/Nerdctl) and local runtime not opted in "
                + "(set sandbox.allow-local-runtime=true to enable host execution)";
        log.warn("[RuntimeFactory] No sandbox runtime available (fail-closed). {}", selectionReason);
    }

    /**
     * Get the selected runtime.
     *
     * @return the active {@link SandboxRuntime}
     * @throws IllegalStateException if no runtime is available (fail-closed)
     */
    public SandboxRuntime getRuntime() {
        if (selectedRuntime == null) {
            throw new IllegalStateException(
                    "No sandbox runtime is available. " + selectionReason
                            + " Install Docker, Podman, or Nerdctl, or set sandbox.allow-local-runtime=true "
                            + "to opt into host-local execution.");
        }
        return selectedRuntime;
    }

    /** Whether a runtime was successfully selected at boot. */
    public boolean isAvailable() {
        return selectedRuntime != null;
    }

    /** Human-readable reason for the current selection (for health checks / diagnostics). */
    public String selectionReason() {
        return selectionReason;
    }

    /** The selected runtime id ("docker" / "podman" / "nerdctl" / "local"), or "none" if fail-closed. */
    public String selectedRuntimeId() {
        return selectedRuntime != null ? selectedRuntime.runtimeId() : "none";
    }
}
