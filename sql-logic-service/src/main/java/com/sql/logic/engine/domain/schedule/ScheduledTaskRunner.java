package com.sql.logic.engine.domain.schedule;

import com.sql.logic.engine.infrastructure.po.ScheduledTask;
import com.sql.logic.engine.infrastructure.po.ScheduledRun;

/**
 * SPI for executing one scheduled-task invocation.
 *
 * <p>Implementations are Spring beans registered in {@link RunnerRegistry} by their
 * {@link #taskType()}. The default implementation {@code AgenticChatRunner} handles
 * {@code taskType = "chat_replay"} (see {@link ScheduleConstants#DEFAULT_TASK_TYPE})
 * and is also the fallback for null/blank task types.
 */
public interface ScheduledTaskRunner {

    /**
     * Execute one scheduled task invocation.
     *
     * @param task the task definition (payload, userId, timeoutSeconds, taskType, ...)
     * @param run  the pre-created run record (status=running); implementations may
     *             read {@code run.getId()} for logging but MUST NOT mutate or persist
     *             it — the engine owns run-record finalization.
     * @return the run outcome (summary + output conversation id); never null.
     */
    RunResult execute(ScheduledTask task, ScheduledRun run);

    /**
     * The taskType this runner handles. Matched against
     * {@link ScheduledTask#getTaskType()} by {@link RunnerRegistry#resolve(String)}.
     */
    String taskType();
}
