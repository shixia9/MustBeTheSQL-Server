package com.sql.logic.engine.domain.schedule;

public final class ScheduleConstants {
    private ScheduleConstants() {}

    /** scheduled_task.status: 1 = running (enabled), 0 = paused (disabled). */
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_PAUSED = 0;

    /** scheduled_run.status values. */
    public static final String RUN_RUNNING = "running";
    public static final String RUN_SUCCESS = "success";
    public static final String RUN_FAILED  = "failed";
    public static final String RUN_TIMEOUT = "timeout";

    /** Default per-run timeout (seconds) when task.timeout_seconds is null. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;

    /** Default poll interval (ms) — also referenced via @Scheduled fixedDelayString default. */
    public static final long DEFAULT_POLL_INTERVAL_MS = 60_000L;

    /** Redisson lock key prefix per task. */
    public static final String LOCK_PREFIX = "schedule:lock:";

    /** Default task type when null. */
    public static final String DEFAULT_TASK_TYPE = "chat_replay";

    /** Max error message length stored in scheduled_run.error_message. */
    public static final int ERROR_MAX = 2000;

    /** Max result summary length stored in scheduled_run.result_summary. */
    public static final int SUMMARY_MAX = 1024;

    /** Max payload size (bytes) accepted on create/update. */
    public static final int PAYLOAD_MAX_BYTES = 64 * 1024;
}
