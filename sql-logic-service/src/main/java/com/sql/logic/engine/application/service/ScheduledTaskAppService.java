package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.common.dto.ScheduledTaskCreateRequest;
import com.sql.logic.engine.common.dto.ScheduledTaskResponse;
import com.sql.logic.engine.common.dto.ScheduledTaskUpdateRequest;
import com.sql.logic.engine.infrastructure.dao.ScheduledTaskDao;
import com.sql.logic.engine.infrastructure.po.ScheduledTask;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * CRUD + toggle for user-managed scheduled tasks.
 * Tenant scoping is enforced on every read/write by userId ownership validation.
 */
@Service
public class ScheduledTaskAppService {

    private static final Pattern CRON_PATTERN = Pattern.compile(
            "(@(annually|yearly|monthly|weekly|daily|hourly|reboot))"
            + "|(@every\\s+\\d+[smhdw])"
            + "|((((\\d+,)+\\d+|([\\d*?]+)(/|-)\\d+)|\\d+|\\*|\\?) ?){5,7}");

    private final ScheduledTaskDao scheduledTaskDao;

    public ScheduledTaskAppService(ScheduledTaskDao scheduledTaskDao) {
        this.scheduledTaskDao = scheduledTaskDao;
    }

    public List<ScheduledTaskResponse> list(Long userId) {
        QueryWrapper<ScheduledTask> q = new QueryWrapper<>();
        q.eq("user_id", userId).orderByDesc("create_time");
        return scheduledTaskDao.selectList(q).stream().map(this::toResponse).toList();
    }

    public ScheduledTaskResponse create(Long userId, ScheduledTaskCreateRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Task name is required");
        }
        if (request.getCronExpr() == null || request.getCronExpr().isBlank()) {
            throw new IllegalArgumentException("Cron expression is required");
        }
        if (!CRON_PATTERN.matcher(request.getCronExpr().trim()).matches()) {
            throw new IllegalArgumentException("Invalid cron expression: " + request.getCronExpr());
        }
        ScheduledTask row = new ScheduledTask();
        row.setUserId(userId);
        row.setName(request.getName());
        row.setCronExpr(request.getCronExpr());
        row.setTaskType(request.getTaskType());
        row.setPayload(request.getPayload());
        row.setStatus(1);
        row.setCreateTime(new Date());
        row.setUpdateTime(new Date());
        scheduledTaskDao.insert(row);
        return toResponse(row);
    }

    public ScheduledTaskResponse update(Long userId, ScheduledTaskUpdateRequest request) {
        if (request.getId() == null) {
            throw new IllegalArgumentException("Task id is required");
        }
        ScheduledTask row = scheduledTaskDao.selectById(request.getId());
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Scheduled task not found or does not belong to this user");
        }
        if (request.getName() != null) row.setName(request.getName());
        if (request.getCronExpr() != null) {
            if (!CRON_PATTERN.matcher(request.getCronExpr().trim()).matches()) {
                throw new IllegalArgumentException("Invalid cron expression: " + request.getCronExpr());
            }
            row.setCronExpr(request.getCronExpr());
        }
        if (request.getTaskType() != null) row.setTaskType(request.getTaskType());
        if (request.getPayload() != null) row.setPayload(request.getPayload());
        row.setUpdateTime(new Date());
        scheduledTaskDao.updateById(row);
        return toResponse(row);
    }

    public void delete(Long userId, Long taskId) {
        ScheduledTask row = scheduledTaskDao.selectById(taskId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Scheduled task not found or does not belong to this user");
        }
        scheduledTaskDao.deleteById(taskId);
    }

    /**
     * Toggle a task between running (1) and paused (0).
     */
    public ScheduledTaskResponse toggle(Long userId, Long taskId) {
        ScheduledTask row = scheduledTaskDao.selectById(taskId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Scheduled task not found or does not belong to this user");
        }
        row.setStatus(row.getStatus() != null && row.getStatus() == 1 ? 0 : 1);
        row.setUpdateTime(new Date());
        scheduledTaskDao.updateById(row);
        return toResponse(row);
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
        return r;
    }
}
