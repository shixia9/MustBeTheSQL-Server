package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("agent_execution_step")
public class AgentExecutionStep {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long executionId;
    private String nodeName;
    private Integer sequenceNo;
    private String status;
    private Long durationMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private Long latencyMs;
    private String nodeType;
    private String inputData;
    private String outputData;
    private String outputDataJson;
    private LocalDateTime createTime;
}
