package com.sql.logic.engine.domain.schedule;

import com.sql.logic.engine.infrastructure.dao.ScheduledRunDao;
import com.sql.logic.engine.infrastructure.dao.ScheduledTaskDao;
import com.sql.logic.engine.infrastructure.po.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * Startup reconciliation for the schedule module.
 *
 * <p>Runs once on application startup (low priority, after core beans are ready) and
 * performs crash recovery + {@code next_run_time} reconciliation:
 * <ol>
 *   <li>Marks stale {@code scheduled_run} rows with {@code status='running'} (left
 *       behind by a crashed previous instance) as {@code failed} with
 *       {@code error_message='process restart'}. The recovery is SCOPED to runs older
 *       than {@code 2 * default timeout} so that, in a multi-instance deployment, this
 *       instance's restart does NOT clobber a sibling instance's actively-running runs
 *       (a global {@code WHERE status='running'} update would do exactly that).</li>
 *   <li>Recomputes {@code next_run_time} for every enabled task. Invalid cron rows are
 *       logged at WARN and skipped (left with a null {@code next_run_time}, so the
 *       poller will not pick them up); they are NOT deleted.</li>
 * </ol>
 *
 * <p>Failures here must NOT prevent application startup: the whole body is wrapped in a
 * try/catch that logs and continues.
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ScheduledTaskBootstrap implements ApplicationRunner {

    private final ScheduledTaskDao scheduledTaskDao;
    private final ScheduledRunDao scheduledRunDao;

    @Value("${schedule.run-timeout-default-s:600}")
    private int defaultTimeout;

    public ScheduledTaskBootstrap(ScheduledTaskDao scheduledTaskDao, ScheduledRunDao scheduledRunDao) {
        this.scheduledTaskDao = scheduledTaskDao;
        this.scheduledRunDao = scheduledRunDao;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // 1. Crash recovery: finalize stale 'running' runs from the previous process.
            //    Only touch runs older than 2x the default timeout — runs younger than
            //    that may still be legitimately executing on a SIBLING instance in a
            //    multi-node deployment, and must NOT be marked failed here.
            int recovered = 0;
            int staleThresholdSec = 2 * defaultTimeout;
            try {
                Date staleBefore = new Date(System.currentTimeMillis() - staleThresholdSec * 1000L);
                recovered = scheduledRunDao.markStaleRunningAsFailed("process restart", staleBefore);
            } catch (Throwable t) {
                log.error("[ScheduledTaskBootstrap] Failed to mark stale running runs as failed", t);
            }
            log.info("[ScheduledTaskBootstrap] recovered {} stale running scheduled runs (threshold={}s)",
                    recovered, staleThresholdSec);

            // 2. Reconcile next_run_time for all enabled tasks.
            int reconciled = 0;
            List<ScheduledTask> enabled;
            try {
                enabled = scheduledTaskDao.selectAllEnabled();
            } catch (Throwable t) {
                log.error("[ScheduledTaskBootstrap] Failed to load enabled tasks", t);
                log.info("[ScheduledTaskBootstrap] schedule bootstrap complete: 0 enabled tasks reconciled");
                return;
            }
            if (enabled != null) {
                Date now = new Date();
                for (ScheduledTask task : enabled) {
                    if (task == null) {
                        continue;
                    }
                    Long taskId = task.getId();
                    try {
                        Date next = CronUtil.nextRunTime(task.getCronExpr(), task.getTimeZone(), now);
                        if (next == null) {
                            log.warn("[ScheduledTaskBootstrap] skipping task {}: invalid cron {}",
                                    taskId, task.getCronExpr());
                            // leave next_run_time null — poller will not pick it up
                            continue;
                        }
                        task.setNextRunTime(next);
                        scheduledTaskDao.updateById(task);
                        reconciled++;
                    } catch (Throwable t) {
                        log.warn("[ScheduledTaskBootstrap] skipping task {}: invalid cron {} ({})",
                                taskId, task.getCronExpr(), t.getMessage());
                    }
                }
            }
            log.info("[ScheduledTaskBootstrap] schedule bootstrap complete: {} enabled tasks reconciled",
                    reconciled);
        } catch (Throwable t) {
            // Bootstrap failures must NOT prevent app startup.
            log.error("[ScheduledTaskBootstrap] schedule bootstrap failed (non-fatal)", t);
        }
    }
}
