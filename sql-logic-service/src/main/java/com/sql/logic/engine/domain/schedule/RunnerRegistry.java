package com.sql.logic.engine.domain.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sql.logic.engine.infrastructure.po.ScheduledRun;
import com.sql.logic.engine.infrastructure.po.ScheduledTask;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a {@link ScheduledTaskRunner} by {@code taskType}.
 *
 * <p>On construction Spring injects every bean implementing {@link ScheduledTaskRunner};
 * they are indexed by {@link ScheduledTaskRunner#taskType()}. Resolution rules:
 * <ul>
 *   <li>blank/null {@code taskType} → runner for {@link ScheduleConstants#DEFAULT_TASK_TYPE}
 *       ({@code "chat_replay"});</li>
 *   <li>known {@code taskType} → the matching runner;</li>
 *   <li>unknown {@code taskType} → a built-in {@link NoopRunner} (exposed via
 *       {@link #hasRunner(String)} returning false and {@link NoopRunner#isNoop()}
 *       returning true) so the engine can record a {@code failed} run with message
 *       {@code "no runner for taskType: {type}"} without the resolver itself throwing.</li>
 * </ul>
 */
@Component
public class RunnerRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunnerRegistry.class);

    private final Map<String, ScheduledTaskRunner> runners = new LinkedHashMap<>();

    public RunnerRegistry(List<ScheduledTaskRunner> runnerBeans) {
        if (runnerBeans != null) {
            for (ScheduledTaskRunner r : runnerBeans) {
                String type = r.taskType();
                if (type == null || type.isBlank()) {
                    log.warn("[RunnerRegistry] Skipping runner with blank taskType: {}", r.getClass().getName());
                    continue;
                }
                ScheduledTaskRunner prev = runners.put(type, r);
                if (prev != null) {
                    log.warn("[RunnerRegistry] Duplicate taskType '{}' — runner {} replaced {}",
                            type, r.getClass().getName(), prev.getClass().getName());
                }
                log.info("[RunnerRegistry] Registered runner '{}' for taskType='{}'",
                        r.getClass().getSimpleName(), type);
            }
        }
        log.info("[RunnerRegistry] Initialized with {} runner(s): {}", runners.size(), runners.keySet());
    }

    /**
     * Resolve the runner for a taskType. Never returns null — unknown types yield a
     * {@link NoopRunner} (check {@link #hasRunner(String)} / {@link NoopRunner#isNoop()}).
     */
    public ScheduledTaskRunner resolve(String taskType) {
        String type = taskType;
        if (type == null || type.isBlank()) {
            type = ScheduleConstants.DEFAULT_TASK_TYPE;
        }
        ScheduledTaskRunner runner = runners.get(type);
        if (runner != null) {
            return runner;
        }
        return NoopRunner.INSTANCE;
    }

    /** True if a real runner is registered for this taskType (blank → default type). */
    public boolean hasRunner(String taskType) {
        String type = (taskType == null || taskType.isBlank())
                ? ScheduleConstants.DEFAULT_TASK_TYPE : taskType;
        return runners.containsKey(type);
    }

    /**
     * True if the given runner is the {@link NoopRunner} placeholder returned for an
     * unregistered taskType. Lets the engine (same package) detect "no runner" without
     * casting to the nested type — it can then record a {@code failed} run with the
     * canonical {@code "no runner for taskType: {type}"} message.
     */
    public boolean isNoop(ScheduledTaskRunner runner) {
        return runner instanceof NoopRunner;
    }

    /**
     * Built-in placeholder returned for unregistered taskTypes. Its {@link #isNoop()}
     * flag lets the engine distinguish "no runner" from a real failure and record the
     * run as {@code failed} with the canonical
     * {@code "no runner for taskType: {type}"} message.
     */
    static final class NoopRunner implements ScheduledTaskRunner {

        static final NoopRunner INSTANCE = new NoopRunner();
        private static final String NOOP_TYPE = "__noop__";

        @Override
        public RunResult execute(ScheduledTask task, ScheduledRun run) {
            return new RunResult(null, null);
        }

        @Override
        public String taskType() {
            return NOOP_TYPE;
        }

        boolean isNoop() {
            return true;
        }
    }
}
