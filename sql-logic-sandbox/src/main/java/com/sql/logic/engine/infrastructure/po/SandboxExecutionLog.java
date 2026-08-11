package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persistent object for {@code sandbox_execution_log} — one row per sandbox code
 * execution (agent-driven or manual). Backs the Task 8 audit trail.
 */
@Data
@TableName("sandbox_execution_log")
public class SandboxExecutionLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String threadId;
    private Long userId;
    private String sessionId;
    private String language;
    /** docker / local / legacy. */
    private String runtime;
    private String code;
    private String stdout;
    private String stderr;
    private Integer exitCode;
    /** success / error / timeout / resource_limit. */
    private String status;
    private Long durationMs;
    private Integer timedOut;
    private LocalDateTime createdAt;
}
