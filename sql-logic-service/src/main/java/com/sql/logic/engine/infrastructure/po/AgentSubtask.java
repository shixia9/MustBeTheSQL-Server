package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Phase B — Agent subtask produced by {@code TaskSplitNode} during a COMPLEX workflow.
 * One row per subtask; sequence progresses 1..N. The {@code result} JSON holds the
 * summarized output emitted by the executing node (SQL result preview, Python
 * analysis snippet, etc.) for the {@code SummarizeNode} to aggregate.
 *
 * Status lifecycle: PENDING → RUNNING → SUCCESS | FAILED | SKIPPED.
 */
@Data
@TableName("agent_subtask")
public class AgentSubtask {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String threadId;
    private String parentThreadId;
    private Long userId;
    private Long workspaceId;
    private Integer seq;
    private String instruction;
    private String status;      // PENDING | RUNNING | SUCCESS | FAILED | SKIPPED
    private String result;      // JSON serialized result blob
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}