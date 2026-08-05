package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Nerdctl sandbox runtime.
 *
 * <p>Thin subclass of {@link ContainerCliRuntime} that probes nerdctl via
 * {@code nerdctl version} and creates sessions using the {@code nerdctl} CLI
 * binary (or the operator-configured override path).
 *
 * <p>Nerdctl is a Docker-compatible CLI for containerd. It provides a similar
 * user experience to Docker but talks directly to containerd rather than
 * dockerd. The CLI interface is Docker-compatible, so
 * {@link ContainerCliSandboxSession} works unchanged.
 *
 * <p>Registered as a Spring {@code @Component}; the {@link RuntimeFactory} selects
 * it when both Docker and Podman are unavailable but nerdctl is present (third
 * priority).
 */
@Component
public class NerdctlSandboxRuntime extends ContainerCliRuntime {

    public NerdctlSandboxRuntime(SandboxProperties properties) {
        super(properties);
    }

    @Override
    protected String cliBinary() {
        return properties.effectiveNerdctlBinary();
    }

    @Override
    public String runtimeId() {
        return "nerdctl";
    }

    /**
     * Check whether the nerdctl CLI is available.
     * Uses {@code nerdctl version} as the probe (nerdctl is daemonless;
     * {@code info} may hang if containerd isn't running).
     */
    @Override
    public boolean isCliAvailable() {
        return isNerdctlAvailable();
    }

    /** Check whether the nerdctl CLI is available and containerd responds. */
    public boolean isNerdctlAvailable() {
        try {
            Process p = new ProcessBuilder(properties.effectiveNerdctlBinary(),
                    "version").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
