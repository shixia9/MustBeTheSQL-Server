package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_execution")
public class AgentExecution {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long connectionId;
    private Long workspaceId;
    private String schemaName;
    private String input;
    private String summary;
    private String status;
    private String threadId;
    private Long conversationId;
    private Integer totalTokens;
    private Integer modelCalls;
    private Integer toolCalls;
    private Long totalDurationMs;
    private LocalDateTime createTime;
}
