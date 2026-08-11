package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Docker sandbox runtime.
 *
 * <p>Thin subclass of {@link ContainerCliRuntime} that probes the Docker daemon
 * via {@code docker info --format {{.ServerVersion}}} and creates sessions using
 * the {@code docker} CLI binary (or the operator-configured override path).
 *
 * <p>Registered as a Spring {@code @Component}; the {@link RuntimeFactory} decides
 * at boot whether to activate it based on availability.
 */
@Component
public class DockerSandboxRuntime extends ContainerCliRuntime {

    public DockerSandboxRuntime(SandboxProperties properties) {
        super(properties);
    }

    @Override
    protected String cliBinary() {
        return properties.effectiveDockerBinary();
    }

    @Override
    public String runtimeId() {
        return "docker";
    }

    /**
     * Check whether the Docker CLI is available and the daemon responds.
     * Uses {@code docker info --format {{.ServerVersion}}} as the probe.
     */
    @Override
    public boolean isCliAvailable() {
        return isDockerAvailable();
    }

    /** Check whether the Docker CLI is available and the daemon responds. */
    public boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder(properties.effectiveDockerBinary(),
                    "info", "--format", "{{.ServerVersion}}").start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
