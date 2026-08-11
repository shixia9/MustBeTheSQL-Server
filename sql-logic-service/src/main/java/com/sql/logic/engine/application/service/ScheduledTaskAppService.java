package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sql.logic.engine.common.dto.RunListResponse;
import com.sql.logic.engine.common.dto.ScheduledRunResponse;
import com.sql.logic.engine.common.dto.ScheduledTaskCreateRequest;
import com.sql.logic.engine.common.dto.ScheduledTaskResponse;
import com.sql.logic.engine.common.dto.ScheduledTaskUpdateRequest;
import com.sql.logic.engine.common.exception.BizException;
import com.sql.logic.engine.domain.schedule.CronUtil;
import com.sql.logic.engine.domain.schedule.ScheduleConstants;
import com.sql.logic.engine.domain.schedule.ScheduledTaskEngine;
import com.sql.logic.engine.infrastructure.dao.ScheduledRunDao;
import com.sql.logic.engine.infrastructure.dao.ScheduledTaskDao;
import com.sql.logic.engine.infrastructure.po.ScheduledRun;
import com.sql.logic.engine.infrastructure.po.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Orchestration layer for user-managed scheduled tasks.
 *
 * <p>Enforces tenant isolation via {@code userId} ownership on every operation,
 * validates cron / timezone / payload size, keeps the DB row's {@code next_run_time}
 * in sync with the cron expression (dual-write consistency), and delegates actual
 * execution to {@link ScheduledTaskEngine} (manual-run + run-history).
 *
 * <p>Replaces the old regex-based CRUD service. Cron validation now uses Spring's
 * {@link org.springframework.scheduling.support.CronExpression} via {@link CronUtil}.
 */
@Slf4j
@Service
public class ScheduledTaskAppService {

    private final ScheduledTaskDao scheduledTaskDao;
    private final ScheduledRunDao scheduledRunDao;
    private final ScheduledTaskEngine scheduledTaskEngine;
    private final ObjectMapper objectMapper;

    public ScheduledTaskAppService(ScheduledTaskDao scheduledTaskDao,
                                  ScheduledRunDao scheduledRunDao,
                                  ScheduledTaskEngine scheduledTaskEngine,
                                  ObjectMapper objectMapper) {
        this.scheduledTaskDao = scheduledTaskDao;
        this.scheduledRunDao = scheduledRunDao;
        this.scheduledTaskEngine = scheduledTaskEngine;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------

    /**
     * List the caller's scheduled tasks, newest-first. When {@code enabledOnly}
     * is set, only tasks with {@code status = 1} (running) are returned.
     */
    public List<ScheduledTaskResponse> list(Long userId, boolean enabledOnly) {
        QueryWrapper<ScheduledTask> q = new QueryWrapper<>();
        q.eq("user_id", userId).orderByDesc("create_time");
        if (enabledOnly) {
            q.eq("status", ScheduleConstants.STATUS_RUNNING);
        }
        return scheduledTaskDao.selectList(q).stream().map(this::toResponse).toList();
    }

    /**
     * Return a single owned task. Throws {@link BizException}(404) if missing or
     * not owned by {@code userId}.
     */
    public ScheduledTaskResponse get(Long userId, Long taskId) {
        return toResponse(loadOwned(userId, taskId));
    }

    // ---------------------------------------------------------------------
    // Mutations (all @Transactional)
    // ---------------------------------------------------------------------

    /**
     * Create a new scheduled task. Validates input, inserts the row, then computes
     * and persists {@code next_run_time}. If the post-insert computation fails the
     * inserted row is deleted (dual-write rollback) and a {@link BizException}(500)
     * is thrown.
     */
    @Transactional(rollbackFor = Exception.class)
    public ScheduledTaskResponse create(Long userId, ScheduledTaskCreateRequest req) {
        validateCreateRequest(req);

        String normalizedCron = safeValidateCron(req.getCronExpr());
        safeValidateZone(req.getTimeZone());
        checkPayloadSize(req.getPayload());

        ScheduledTask row = new ScheduledTask();
        row.setUserId(userId);
        row.setName(req.getName());
        row.setCronExpr(normalizedCron);
        row.setTaskType(req.getTaskType());
        row.setPayload(req.getPayload());
        row.setDescription(req.getDescription());
        row.setTimeZone(req.getTimeZone());
        row.setTimeoutSeconds(req.getTimeoutSeconds());
        row.setMaxRetries(req.getMaxRetries() != null ? req.getMaxRetries() : 0);
        row.setPayloadVersion(req.getPayloadVersion() != null ? req.getPayloadVersion() : 1);
        row.setStatus(ScheduleConstants.STATUS_RUNNING);
        Date now = new Date();
        row.setCreateTime(now);
        row.setUpdateTime(now);

        scheduledTaskDao.insert(row);

        // Compute + persist next_run_time. On failure, roll back the insert.
        try {
            Date next = CronUtil.nextRunTime(normalizedCron, row.getTimeZone(), new Date());
            row.setNextRunTime(next);
            scheduledTaskDao.updateById(row);
        } catch (Exception e) {
            log.error("Failed to initialize schedule, rolling back: userId={}, taskId={}",
                    userId, row.getId(), e);
            try {
                scheduledTaskDao.deleteById(row.getId());
            } catch (Exception cleanupEx) {
                log.warn("Rollback deleteById failed: taskId={}", row.getId(), cleanupEx);
            }
            throw new BizException(500, "Failed to initialize schedule: " + e.getMessage());
        }

        log.info("created scheduled task: userId={}, taskId={}, cron={}",
                userId, row.getId(), normalizedCron);
        return toResponse(row);
    }

    /**
     * Partial update of an owned task. Non-null fields are applied; cron/timezone
     * changes trigger a {@code next_run_time} recompute. Payload is JSON-merged
     * (new keys overwrite existing keys, remaining keys preserved); falls back to
     * a wholesale replace if either side is not a valid JSON object.
     */
    @Transactional(rollbackFor = Exception.class)
    public ScheduledTaskResponse update(Long userId, Long taskId, ScheduledTaskUpdateRequest req) {
        ScheduledTask row = loadOwned(userId, taskId);

        if (req.getName() != null) row.setName(req.getName());
        if (req.getTaskType() != null) row.setTaskType(req.getTaskType());
        if (req.getDescription() != null) row.setDescription(req.getDescription());
        if (req.getMaxRetries() != null) row.setMaxRetries(req.getMaxRetries());

        if (req.getTimeoutSeconds() != null) {
            if (req.getTimeoutSeconds() <= 0) {
                throw new BizException(400, "timeoutSeconds must be positive");
            }
            row.setTimeoutSeconds(req.getTimeoutSeconds());
        }

        boolean tzChanged = false;
        if (req.getTimeZone() != null) {
            safeValidateZone(req.getTimeZone());
            row.setTimeZone(req.getTimeZone());
            tzChanged = true;
        }

        if (req.getPayload() != null) {
            checkPayloadSize(req.getPayload());
            row.setPayload(mergePayload(row.getPayload(), req.getPayload()));
        }

        boolean cronChanged = false;
        if (req.getCronExpr() != null && !req.getCronExpr().isBlank()) {
            String normalized = safeValidateCron(req.getCronExpr());
            row.setCronExpr(normalized);
            cronChanged = true;
        }

        row.setUpdateTime(new Date());
        scheduledTaskDao.updateById(row);

        if (cronChanged || tzChanged) {
            Date next = CronUtil.nextRunTime(row.getCronExpr(), row.getTimeZone(), new Date());
            if (next == null) {
                log.warn("recompute next_run_time returned null after cron/tz change: userId={}, taskId={}, cron={}",
                        userId, taskId, row.getCronExpr());
            }
            row.setNextRunTime(next);
            scheduledTaskDao.updateById(row);
        }

        log.info("updated scheduled task: userId={}, taskId={}, cronOrTzChanged={}",
                userId, taskId, cronChanged || tzChanged);
        return toResponse(row);
    }

    /**
     * Toggle a task between running (1) and paused (0). When enabling, recompute
     * {@code next_run_time} so the poller picks it up promptly. When disabling, the
     * poller will simply skip it (status check).
     */
    @Transactional(rollbackFor = Exception.class)
    public ScheduledTaskResponse toggle(Long userId, Long taskId, boolean enabled) {
        ScheduledTask row = loadOwned(userId, taskId);
        row.setStatus(enabled ? ScheduleConstants.STATUS_RUNNING : ScheduleConstants.STATUS_PAUSED);
        if (enabled) {
            row.setNextRunTime(CronUtil.nextRunTime(row.getCronExpr(), row.getTimeZone(), new Date()));
        }
        row.setUpdateTime(new Date());
        scheduledTaskDao.updateById(row);
        log.info("toggled scheduled task: userId={}, taskId={}, enabled={}", userId, taskId, enabled);
        return toResponse(row);
    }

    /**
     * Delete an owned task and all of its run history. Runs in a single transaction
     * so both succeed or both roll back. The poller will simply not find the row on
     * its next cycle (best-effort engine cleanup).
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long taskId) {
        loadOwned(userId, taskId);
        scheduledRunDao.deleteByTask(taskId);
        scheduledTaskDao.deleteById(taskId);
        log.info("deleted scheduled task: userId={}, taskId={}", userId, taskId);
    }

    // ---------------------------------------------------------------------
    // Run history (read)
    // ---------------------------------------------------------------------

    /**
     * Paginated run history for an owned task, newest-first. {@code limit} is
     * clamped to 1..200, {@code offset} to >= 0.
     */
    public RunListResponse listRuns(Long userId, Long taskId, int limit, int offset) {
        loadOwned(userId, taskId);
        int clampedLimit = Math.max(1, Math.min(limit, 200));
        int clampedOffset = Math.max(0, offset);
        List<ScheduledRun> rows = scheduledRunDao.selectByTask(taskId, clampedLimit, clampedOffset);
        long total = scheduledRunDao.countByTask(taskId);
        return new RunListResponse(rows.stream().map(this::toRunResponse).toList(), total);
    }

    /**
     * Return a single run of an owned task. Throws {@link BizException}(404) if the
     * run does not exist or does not belong to {@code taskId} (no ownership leak).
     */
    public ScheduledRunResponse getRun(Long userId, Long taskId, Long runId) {
        loadOwned(userId, taskId);
        ScheduledRun run = scheduledRunDao.selectByRunId(runId);
        if (run == null || !run.getTaskId().equals(taskId)) {
            throw new BizException(404, "Run not found");
        }
        return toRunResponse(run);
    }

    // ---------------------------------------------------------------------
    // Manual trigger (delegates to engine)
    // ---------------------------------------------------------------------

    /**
     * Trigger an immediate one-off execution of an owned task. The task's
     * {@code status} is not changed (a paused task may be run manually). The engine
     * returns the created {@code running} run record synchronously; finalization
     * proceeds asynchronously. The engine throws {@link BizException}(404 / 409)
     * which propagate to the controller.
     */
    public ScheduledRunResponse manualRun(Long userId, Long taskId) {
        loadOwned(userId, taskId);
        ScheduledRun run = scheduledTaskEngine.manualRun(taskId);
        log.info("manual run triggered: userId={}, taskId={}, runId={}", userId, taskId, run.getId());
        return toRunResponse(run);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Load a task and verify ownership; 404 if missing or not owned. */
    private ScheduledTask loadOwned(Long userId, Long taskId) {
        ScheduledTask row = scheduledTaskDao.selectById(taskId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BizException(404, "Scheduled task not found");
        }
        return row;
    }

    private void validateCreateRequest(ScheduledTaskCreateRequest req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(400, "Task name is required");
        }
        if (req.getCronExpr() == null || req.getCronExpr().isBlank()) {
            throw new BizException(400, "Cron expression is required");
        }
        if (req.getTimeoutSeconds() != null && req.getTimeoutSeconds() <= 0) {
            throw new BizException(400, "timeoutSeconds must be positive");
        }
    }

    private String safeValidateCron(String cron) {
        try {
            return CronUtil.validate(cron);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, e.getMessage());
        }
    }

    private void safeValidateZone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return;
        }
        try {
            CronUtil.validateZone(timeZone);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, e.getMessage());
        }
    }

    private void checkPayloadSize(String payload) {
        if (payload != null && !payload.isBlank()
                && payload.getBytes(StandardCharsets.UTF_8).length > ScheduleConstants.PAYLOAD_MAX_BYTES) {
            throw new BizException(400, "Payload too large");
        }
    }

    /**
     * Merge {@code incoming} JSON over {@code existing} (new keys overwrite, others
     * preserved). Falls back to {@code incoming} if either side is not a JSON object
     * or parsing fails.
     */
    private String mergePayload(String existing, String incoming) {
        if (incoming == null) return existing;
        if (existing == null || existing.isBlank()) return incoming;
        try {
            JsonNode existingNode = objectMapper.readTree(existing);
            JsonNode incomingNode = objectMapper.readTree(incoming);
            if (existingNode.isObject() && incomingNode.isObject()) {
                ObjectNode merged = (ObjectNode) existingNode.deepCopy();
                Iterator<Map.Entry<String, JsonNode>> fields = incomingNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    merged.set(entry.getKey(), entry.getValue());
                }
                return objectMapper.writeValueAsString(merged);
            }
        } catch (Exception e) {
            log.debug("payload merge fell back to wholesale replace: {}", e.getMessage());
        }
        return incoming;
    }

    private ScheduledTaskResponse toResponse(ScheduledTask row) {
        ScheduledTaskResponse r = new ScheduledTaskResponse();
        r.setId(row.getId());
        r.setName(row.getName());
        r.setCronExpr(row.getCronExpr());
        r.setTaskType(row.getTaskType());
        r.setPayload(row.getPayload());
        r.setStatus(row.getStatus());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        r.setLastRunTime(row.getLastRunTime() != null ? sdf.format(row.getLastRunTime()) : null);
        r.setNextRunTime(row.getNextRunTime() != null ? sdf.format(row.getNextRunTime()) : null);
        r.setCreateTime(row.getCreateTime() != null ? sdf.format(row.getCreateTime()) : null);
        r.setUpdateTime(row.getUpdateTime() != null ? sdf.format(row.getUpdateTime()) : null);
        r.setDescription(row.getDescription());
        r.setTimeZone(row.getTimeZone());
        r.setTimeoutSeconds(row.getTimeoutSeconds());
        r.setMaxRetries(row.getMaxRetries());
        r.setLastRunStatus(row.getLastRunStatus());
        r.setLastRunId(row.getLastRunId());
        r.setPayloadVersion(row.getPayloadVersion());
        return r;
    }

    private ScheduledRunResponse toRunResponse(ScheduledRun row) {
        ScheduledRunResponse r = new ScheduledRunResponse();
        r.setId(row.getId());
        r.setTaskId(row.getTaskId());
        r.setStatus(row.getStatus());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        r.setStartedAt(row.getStartedAt() != null ? sdf.format(row.getStartedAt()) : null);
        r.setFinishedAt(row.getFinishedAt() != null ? sdf.format(row.getFinishedAt()) : null);
        r.setResultSummary(row.getResultSummary());
        r.setErrorMessage(row.getErrorMessage());
        r.setOutputConversationId(row.getOutputConversationId());
        r.setAttempt(row.getAttempt());
        r.setCreateTime(row.getCreateTime() != null ? sdf.format(row.getCreateTime()) : null);
        return r;
    }
}
