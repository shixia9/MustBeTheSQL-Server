package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Scheduled task execution history — one row per task trigger.
 * <p>
 * Records the lifecycle of a single run (running → success / failed / timeout),
 * the result summary produced by the runner, and any error message. Mirrors the
 * DB-GPT {@code scheduled_run} blueprint.
 */
@Data
@TableName("scheduled_run")
public class ScheduledRun {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private Date startedAt;
    private Date finishedAt;
    /** running / success / failed / timeout */
    private String status;
    private String resultSummary;
    private String errorMessage;
    private String outputConversationId;
    private Integer attempt;
    private Date createTime;
}
