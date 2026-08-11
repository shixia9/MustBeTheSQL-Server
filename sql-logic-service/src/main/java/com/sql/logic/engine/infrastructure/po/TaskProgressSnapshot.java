package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persisted task progress snapshot for the never-lost progress tracker.
 * <p>
 * Survives context compression and agent restarts. Each record represents
 * one completed or failed step in a multi-step agent workflow, keyed by
 * conversation ID (convId).
 */
@Data
@TableName("task_progress_snapshot")
public class TaskProgressSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Conversation / thread identifier. */
    private String convId;

    /** Step number within the conversation. */
    private Integer stepNumber;

    /** Action description (what was done). */
    private String action;

    /** Execution phase (e.g., SQL_GENERATION, PYTHON_EXECUTION). */
    private String phase;

    /** Step status: DONE or FAILED. */
    private String status;

    /** Snapshot of the result at this step. */
    private String snapshot;

    /** Optional LLM model used for this step. */
    private String modelName;

    /** Creation timestamp. */
    private LocalDateTime createTime;
}
