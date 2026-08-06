package com.sql.logic.engine.domain.sandbox.execution;

import com.sql.logic.engine.domain.sandbox.config.SandboxConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-session configuration.
 *
 * <p>Carries the language, resource limits, working directory, environment
 * variables, and network policy for one sandbox session. Immutable by design;
 * builders are provided for the common cases.
 *
 * @param language        language key (python/javascript/java/cpp/go/rust/shell)
 * @param timeoutSeconds  max wall-clock execution time per {@code execute()} call
 * @param maxMemoryBytes  memory cgroup limit for the sandbox process/container
 * @param maxCpus         CPU count limit (cgroup {@code cpuset})
 * @param workingDir      working directory inside the sandbox
 * @param environmentVars environment variables injected into the sandbox
 * @param networkDisabled when true, the sandbox has no network access
 */
public record SessionConfig(
        String language,
        int timeoutSeconds,
        long maxMemoryBytes,
        int maxCpus,
        String workingDir,
        Map<String, String> environmentVars,
        boolean networkDisabled
) {
    public SessionConfig {
        if (language == null || language.isBlank()) {
            language = "python";
        }
        language = language.toLowerCase();
        if (workingDir == null || workingDir.isBlank()) {
            workingDir = SandboxConfig.WORKING_DIR;
        }
        environmentVars = environmentVars == null ? Map.of() : Map.copyOf(environmentVars);
    }

    /** Python session with project defaults (256MB, 1 CPU, 30s, network disabled). */
    public static SessionConfig python() {
        return forLanguage("python");
    }

    /** Session for the given language with project defaults applied. */
    public static SessionConfig forLanguage(String language) {
        return builder(language).build();
    }

    /** Fluent builder seeded with project defaults for the given language. */
    public static Builder builder(String language) {
        return new Builder(language);
    }

    /** Mutable builder for {@link SessionConfig}. */
    public static final class Builder {
        private final String language;
        private int timeoutSeconds = SandboxConfig.MAX_EXECUTION_TIME;
        private long maxMemoryBytes = SandboxConfig.MAX_MEMORY;
        private int maxCpus = 1;
        private String workingDir = SandboxConfig.WORKING_DIR;
        private Map<String, String> environmentVars = new LinkedHashMap<>();
        private boolean networkDisabled = true;

        Builder(String language) {
            this.language = language;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder maxMemoryBytes(long maxMemoryBytes) {
            this.maxMemoryBytes = maxMemoryBytes;
            return this;
        }

        public Builder maxCpus(int maxCpus) {
            this.maxCpus = maxCpus;
            return this;
        }

        public Builder workingDir(String workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        public Builder environmentVar(String key, String value) {
            this.environmentVars.put(key, value);
            return this;
        }

        public Builder environmentVars(Map<String, String> vars) {
            this.environmentVars.putAll(vars);
            return this;
        }

        public Builder networkDisabled(boolean networkDisabled) {
            this.networkDisabled = networkDisabled;
            return this;
        }

        public SessionConfig build() {
            return new SessionConfig(language, timeoutSeconds, maxMemoryBytes, maxCpus,
                    workingDir, environmentVars, networkDisabled);
        }
    }
}
