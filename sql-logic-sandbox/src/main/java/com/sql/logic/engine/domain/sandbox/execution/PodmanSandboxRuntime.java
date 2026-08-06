package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Podman sandbox runtime.
 *
 * <p>Thin subclass of {@link ContainerCliRuntime} that probes Podman via
 * {@code podman info --format {{.ServerVersion}}} and creates sessions using
 * the {@code podman} CLI binary (or the operator-configured override path).
 *
 * <p>Podman is rootless by default and daemonless (uses fork/exec model), which
 * provides slightly different isolation characteristics than Docker's daemon
 * model. The CLI interface is Docker-compatible, so {@link ContainerCliSandboxSession}
 * works unchanged.
 *
 * <p>Registered as a Spring {@code @Component}; the {@link RuntimeFactory} selects
 * it when Docker is unavailable but Podman is present (second priority).
 */
@Component
public class PodmanSandboxRuntime extends ContainerCliRuntime {

    public PodmanSandboxRuntime(SandboxProperties properties) {
        super(properties);
    }

    @Override
    protected String cliBinary() {
        return properties.effectivePodmanBinary();
    }

    @Override
    public String runtimeId() {
        return "podman";
    }

    /**
     * Check whether the Podman CLI is available and responds.
     * Uses {@code podman info --format {{.ServerVersion}}} as the probe.
     */
    @Override
    public boolean isCliAvailable() {
        return isPodmanAvailable();
    }

    /** Check whether the Podman CLI is available and responds. */
    public boolean isPodmanAvailable() {
        try {
            Process p = new ProcessBuilder(properties.effectivePodmanBinary(),
                    "info", "--format", "{{.ServerVersion}}").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
