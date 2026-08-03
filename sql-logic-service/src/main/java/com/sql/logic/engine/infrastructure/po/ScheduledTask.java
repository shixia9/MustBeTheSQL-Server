package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Cron-based recurring task owned by a user.
 */
@Data
@TableName("scheduled_task")
public class ScheduledTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String description;
    private String cronExpr;
    private String timeZone;
    private Integer timeoutSeconds;
    private Integer maxRetries;
    private String taskType;
    private String payload;
    private Integer payloadVersion;
    /** 0 = paused, 1 = running */
    private Integer status;
    /** success / failed / timeout */
    private String lastRunStatus;
    private Long lastRunId;
    private Date lastRunTime;
    private Date nextRunTime;
    private Date createTime;
    private Date updateTime;
}
