package com.sql.logic.engine.domain.schedule;

import com.sql.logic.engine.common.exception.BizException;
import com.sql.logic.engine.infrastructure.dao.ScheduledRunDao;
import com.sql.logic.engine.infrastructure.dao.ScheduledTaskDao;
import com.sql.logic.engine.infrastructure.po.ScheduledRun;
import com.sql.logic.engine.infrastructure.po.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.sql.logic.engine.domain.schedule.ScheduleConstants.ERROR_MAX;
import static com.sql.logic.engine.domain.schedule.ScheduleConstants.LOCK_PREFIX;
import static com.sql.logic.engine.domain.schedule.ScheduleConstants.RUN_FAILED;
import static com.sql.logic.engine.domain.schedule.ScheduleConstants.RUN_RUNNING;
import static com.sql.logic.engine.domain.schedule.ScheduleConstants.RUN_SUCCESS;
import static com.sql.logic.engine.domain.schedule.ScheduleConstants.RUN_TIMEOUT;
import static com.sql.logic.engine.domain.schedule.ScheduleConstants.STATUS_RUNNING;
import static com.sql.logic.engine.domain.schedule.ScheduleConstants.SUMMARY_MAX;

/**
 * DB-backed cron poller engine.
 *
 * <p>A {@link Scheduled} fixed-delay poller (default 60s) scans {@code scheduled_task}
 * for due, enabled rows ({@code status = 1 AND next_run_time <= now}) and dispatches
 * each to the registered {@link ScheduledTaskRunner} on the dedicated
 * {@code scheduleExecutor} thread pool. Each execution is guarded by a Redisson
 * distributed lock keyed {@code schedule:lock:{taskId}} so two application instances
 * never run the same task concurrently.
 *
 * <p>Run lifecycle: a {@code scheduled_run} row is created with {@code status=running}
 * <em>before</em> the runner is invoked; on completion it is finalized as
 * {@code success} / {@code failed} / {@code timeout} via {@link CompletableFuture#orTimeout}.
 * The task row's {@code last_run_status} / {@code last_run_time} / {@code last_run_id}
 * are updated after every finalized run; {@code next_run_time} is recomputed on every
 * poller-path finalization (success/failed/timeout) so a failing task does not refire
 * every poll cycle; manual runs do not touch next_run_time.
 *
 * <p>The runner SPI contract is "never throws" — all internal failures are returned as
 * a {@link RunResult} with {@code success=false}. The engine defends against a throw
 * anyway via the {@link CompletionException} catch, which records a {@code failed} run.
 *
 * <p>{@link #manualRun(Long)} exposes an immediate-trigger entry point for the service
 * layer (after ownership check): it ignores {@code task.status} (a paused task may be
 * run manually) and returns the {@code running} run record synchronously while
 * finalization proceeds asynchronously.
 */
@Slf4j
@Component
public class ScheduledTaskEngine {

    private final ScheduledTaskDao scheduledTaskDao;
    private final ScheduledRunDao scheduledRunDao;
    private final RunnerRegistry runnerRegistry;
    private final RedissonClient redissonClient;
    private final ThreadPoolTaskScheduler scheduleExecutor;

    @Value("${schedule.run-timeout-default-s:600}")
    private int defaultTimeout;

    public ScheduledTaskEngine(ScheduledTaskDao scheduledTaskDao,
                               ScheduledRunDao scheduledRunDao,
                               RunnerRegistry runnerRegistry,
                               RedissonClient redissonClient,
                               @Qualifier("scheduleExecutor") ThreadPoolTaskScheduler scheduleExecutor) {
        this.scheduledTaskDao = scheduledTaskDao;
        this.scheduledRunDao = scheduledRunDao;
        this.runnerRegistry = runnerRegistry;
        this.redissonClient = redissonClient;
        this.scheduleExecutor = scheduleExecutor;
    }

    // ---------------------------------------------------------------------
    // Poller
    // ---------------------------------------------------------------------

    /**
     * Fixed-delay poller (default 60s, configurable via {@code schedule.poll-interval-ms}).
     * Selects due enabled tasks and dispatches each to {@link #executeTask(Long)} on the
     * schedule executor — never blocks the poller thread.
     */
    @Scheduled(fixedDelayString = "${schedule.poll-interval-ms:60000}")
    public void poll() {
        Date now = new Date();
        List<ScheduledTask> due = scheduledTaskDao.selectDueEnabled(now);
        if (due == null || due.isEmpty()) {
            log.debug("[ScheduledTaskEngine] poll: no due tasks");
            return;
        }
        log.debug("[ScheduledTaskEngine] poll: {} due task(s)", due.size());
        for (ScheduledTask task : due) {
            Long taskId = task != null ? task.getId() : null;
            try {
                scheduleExecutor.submit(() -> executeTask(taskId));
            } catch (Throwable t) {
                // Submit failure (e.g. executor saturated) — do not abort the whole poll cycle.
                log.error("[ScheduledTaskEngine] Failed to submit task for execution: taskId={}", taskId, t);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Poller execution path
    // ---------------------------------------------------------------------

    /**
     * Execute one due task: reload, status-check, acquire lock, create a running run,
     * dispatch via {@link #doExecute}, then release the lock in a finally block.
     * Safe to call from the schedule executor. No-op if the task was deleted or paused
     * since it was queued, or if another instance already holds the lock.
     */
    public void executeTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        ScheduledTask task = scheduledTaskDao.selectById(taskId);
        if (task == null) {
            log.debug("[ScheduledTaskEngine] executeTask: task not found: taskId={}", taskId);
            return;
        }
        if (task.getStatus() == null || task.getStatus() != STATUS_RUNNING) {
            log.debug("[ScheduledTaskEngine] executeTask: task not running (paused/deleted): taskId={}, status={}",
                    taskId, task.getStatus());
            return;
        }

        int timeout = resolveTimeout(task);
        long leaseTime = timeout + 60L;
        RLock lock = redissonClient.getLock(LOCK_PREFIX + taskId);
        boolean acquired;
        try {
            acquired = lock.tryLock(0L, leaseTime, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[ScheduledTaskEngine] executeTask: interrupted acquiring lock: taskId={}", taskId);
            return;
        }
        if (!acquired) {
            log.debug("[ScheduledTaskEngine] executeTask: already running elsewhere: taskId={}", taskId);
            return;
        }

        ScheduledRun run = newRun(taskId);
        try {
            scheduledRunDao.insert(run);
            log.info("[ScheduledTaskEngine] executeTask: started taskId={}, runId={}", taskId, run.getId());
            doExecute(task, run, false);
        } catch (Throwable t) {
            // Defensive: doExecute is not expected to throw, but never let an exception
            // escape unrecorded — finalize as failed if the run was created.
            log.error("[ScheduledTaskEngine] executeTask: unexpected error: taskId={}, runId={}", taskId, run.getId(), t);
            if (run.getStatus() != null && RUN_RUNNING.equals(run.getStatus())) {
                finalizeRun(run, RUN_FAILED, null, truncate(safeMsg(t), ERROR_MAX), null);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Throwable t) {
                    log.warn("[ScheduledTaskEngine] executeTask: unlock failed: taskId={}", taskId, t);
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Manual trigger entry point
    // ---------------------------------------------------------------------

    /**
     * Manually trigger one task execution immediately. Called by the service layer after
     * the ownership check has passed. Ignores {@code task.status} (a paused task may be
     * run manually); the task's status is not changed.
     *
     * @throws BizException 404 if the task does not exist; 409 if the task is already
     *                       running (lock held by another execution); 503 if the
     *                       executor is saturated.
     */
    public ScheduledRun manualRun(Long taskId) {
        if (taskId == null) {
            throw new BizException(400, "taskId is required");
        }
        ScheduledTask task = scheduledTaskDao.selectById(taskId);
        if (task == null) {
            throw new BizException(404, "Scheduled task not found");
        }

        int timeout = resolveTimeout(task);
        long leaseTime = timeout + 60L;
        RLock lock = redissonClient.getLock(LOCK_PREFIX + taskId);

        // Best-effort synchronous 409 check. isLocked() returns true if ANY thread
        // (on any instance) holds the lock — a fast reject path. The authoritative
        // check is the async tryLock below; the inherent check-then-act race is
        // tolerated by finalizing the run as 'failed' if the async tryLock loses.
        if (lock.isLocked()) {
            throw new BizException(409, "Task is already running, retry later");
        }

        final ScheduledRun run = newRun(taskId);
        scheduledRunDao.insert(run);
        log.info("[ScheduledTaskEngine] manualRun: queued taskId={}, runId={}", taskId, run.getId());

        final ScheduledTask taskRef = task;
        final long leaseRef = leaseTime;
        try {
            scheduleExecutor.submit(() -> {
                boolean acquired = false;
                try {
                    try {
                        acquired = lock.tryLock(0L, leaseRef, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    if (!acquired) {
                        // Lost the race to the poller or another manual trigger — do NOT
                        // run, just finalize the run row we already created.
                        finalizeRun(run, RUN_FAILED, null, "task already running", null);
                        return;
                    }
                    doExecute(taskRef, run, true);
                } catch (Throwable t) {
                    log.error("[ScheduledTaskEngine] manualRun: unexpected error: taskId={}, runId={}",
                            taskId, run.getId(), t);
                    if (run.getStatus() != null && RUN_RUNNING.equals(run.getStatus())) {
                        finalizeRun(run, RUN_FAILED, null, truncate(safeMsg(t), ERROR_MAX), null);
                    }
                } finally {
                    if (acquired && lock.isHeldByCurrentThread()) {
                        try {
                            lock.unlock();
                        } catch (Throwable t) {
                            log.warn("[ScheduledTaskEngine] manualRun: unlock failed: taskId={}", taskId, t);
                        }
                    }
                }
            });
        } catch (RejectedExecutionException ree) {
            // Pool saturated — finalize the run row we already created so it is not
            // orphaned as 'running' until restart, then surface a 503 to the caller.
            log.error("[ScheduledTaskEngine] manualRun: executor rejected task: taskId={}, runId={}",
                    taskId, run.getId(), ree);
            finalizeRun(run, RUN_FAILED, null, "executor saturated, retry later", null);
            throw new BizException(503, "Executor saturated, retry later");
        }

        return run;
    }

    // ---------------------------------------------------------------------
    // Shared execute + finalize (no lock, no run creation — caller owns those)
    // ---------------------------------------------------------------------

    /**
     * Shared execution logic for both poller and manual paths. The caller has already
     * acquired the lock and created the {@code running} run row. Resolves the runner,
     * invokes it with a {@link CompletableFuture#orTimeout} enforcing the timeout, and
     * finalizes the run (success / failed / timeout). Never throws.
     */
    private void doExecute(ScheduledTask task, ScheduledRun run, boolean isManual) {
        Long taskId = task != null ? task.getId() : null;
        Long runId = run != null ? run.getId() : null;
        int timeout = resolveTimeout(task);

        ScheduledTaskRunner runner = runnerRegistry.resolve(task != null ? task.getTaskType() : null);
        if (runnerRegistry.isNoop(runner)) {
            String taskType = task != null ? task.getTaskType() : null;
            log.warn("[ScheduledTaskEngine] doExecute: no runner for taskType={} taskId={} runId={} manual={}",
                    taskType, taskId, runId, isManual);
            finalizeRun(run, RUN_FAILED, null,
                    "no runner for taskType: " + (taskType != null ? taskType : ScheduleConstants.DEFAULT_TASK_TYPE),
                    null);
            // Advance next_run_time too so the poller does not hammer a misconfigured task every cycle.
            updateTaskAfterRun(task, run, RUN_FAILED, isManual);
            return;
        }

        final ScheduledTask taskRef = task;
        final ScheduledRun runRef = run;
        // Run on ForkJoinPool.commonPool() — do NOT pass scheduleExecutor here, otherwise
        // doExecute (already on a scheduleExecutor thread) consumes a SECOND pool thread
        // while blocked on join(), risking pool starvation/deadlock at >=4 concurrent tasks.
        CompletableFuture<RunResult> future = CompletableFuture
                .supplyAsync(() -> runner.execute(taskRef, runRef))
                .orTimeout(timeout, TimeUnit.SECONDS);

        try {
            RunResult result = future.join();
            String status = result.isSuccess() ? RUN_SUCCESS : RUN_FAILED;
            String errMsg = result.isSuccess() ? null : result.getSummary(); // summary holds the error text when !success
            String summary = result.isSuccess() ? result.getSummary() : null;
            finalizeRun(run, status, summary, errMsg, result.getOutputConversationId());
            updateTaskAfterRun(task, run, status, isManual);
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
            if (cause instanceof TimeoutException) {
                log.warn("[ScheduledTaskEngine] doExecute: timeout after {}s taskId={} runId={}", timeout, taskId, runId);
                finalizeRun(run, RUN_TIMEOUT, null, "execution exceeded " + timeout + "s", null);
                updateTaskAfterRun(task, run, RUN_TIMEOUT, isManual);
            } else {
                log.error("[ScheduledTaskEngine] doExecute: runner threw taskId={} runId={}", taskId, runId, cause);
                finalizeRun(run, RUN_FAILED, null, truncate(safeMsg(cause), ERROR_MAX), null);
                updateTaskAfterRun(task, run, RUN_FAILED, isManual);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Run finalization + task update
    // ---------------------------------------------------------------------

    /**
     * Finalize ONLY the run row (status, finished_at, result_summary, error_message,
     * output_conversation_id). The task row's last_run_* fields and next_run_time are
     * advanced separately via {@link #updateTaskAfterRun} on every finalization path
     * (success, failed, timeout) so the poller does not refire a failing task every cycle.
     */
    private void finalizeRun(ScheduledRun run, String status, String summary, String errMsg, String outputConvId) {
        if (run == null) {
            return;
        }
        run.setStatus(status);
        run.setFinishedAt(new Date());
        run.setResultSummary(truncate(summary, SUMMARY_MAX));
        run.setErrorMessage(truncate(errMsg, ERROR_MAX));
        run.setOutputConversationId(outputConvId);
        try {
            scheduledRunDao.updateById(run);
        } catch (Throwable t) {
            log.error("[ScheduledTaskEngine] finalizeRun: failed to update run row: runId={}", run.getId(), t);
        }

        Long runId = run.getId();
        Long taskId = run.getTaskId();
        if (RUN_SUCCESS.equals(status)) {
            log.info("[ScheduledTaskEngine] scheduled run finalized: taskId={}, runId={}, status={}",
                    taskId, runId, status);
        } else {
            log.warn("[ScheduledTaskEngine] scheduled run finalized: taskId={}, runId={}, status={}, error={}",
                    taskId, runId, status, run.getErrorMessage());
        }
    }

    /**
     * Update the task row's last_run_* fields after every finalized run, and — for the
     * poller path only — recompute {@code next_run_time} so a failing/timeout task does
     * not refire every poll cycle. Manual runs never touch next_run_time.
     */
    private void updateTaskAfterRun(ScheduledTask task, ScheduledRun run, String status, boolean isManual) {
        if (task == null || run == null) {
            return;
        }
        try {
            ScheduledTask fresh = scheduledTaskDao.selectById(task.getId());
            if (fresh == null) {
                return;
            }
            fresh.setLastRunStatus(status);
            fresh.setLastRunTime(run.getStartedAt());
            fresh.setLastRunId(run.getId());
            if (!isManual) {
                Date next = CronUtil.nextRunTime(fresh.getCronExpr(), fresh.getTimeZone(), new Date());
                if (next == null) {
                    log.warn("[ScheduledTaskEngine] task {} has invalid cron, next_run_time cleared", task.getId());
                }
                fresh.setNextRunTime(next);
            }
            fresh.setUpdateTime(new Date());
            scheduledTaskDao.updateById(fresh);
            log.info("[ScheduledTaskEngine] scheduled task updated after run: taskId={}, runId={}, status={}, manual={}",
                    task.getId(), run.getId(), status, isManual);
        } catch (Throwable t) {
            log.error("[ScheduledTaskEngine] updateTaskAfterRun: failed to update task after run: taskId={}",
                    task.getId(), t);
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Resolve the effective per-run timeout (seconds), falling back to the configured default. */
    private int resolveTimeout(ScheduledTask task) {
        if (task != null && task.getTimeoutSeconds() != null && task.getTimeoutSeconds() > 0) {
            return task.getTimeoutSeconds();
        }
        return defaultTimeout;
    }

    /** Create a fresh {@code running} run row (not yet persisted). */
    private ScheduledRun newRun(Long taskId) {
        ScheduledRun run = new ScheduledRun();
        run.setTaskId(taskId);
        run.setStartedAt(new Date());
        run.setStatus(RUN_RUNNING);
        run.setAttempt(1);
        return run;
    }

    /** Null-safe truncation. */
    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Best-effort message extraction from a throwable. */
    private static String safeMsg(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        String msg = t.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : t.toString();
    }
}
