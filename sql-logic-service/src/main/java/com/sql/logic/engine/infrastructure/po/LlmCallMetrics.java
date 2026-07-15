package com.sql.logic.engine.infrastructure.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Aggregated LLM call metrics per per-minute window per configId.
 * Mirrors API-Premium-Gateway's {@code api_instance_metrics} sliding-window
 * aggregation pattern.
 */
@Data
@TableName("llm_call_metrics")
public class LlmCallMetrics {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long configId;
    private Long userId;
    private LocalDateTime windowStart;
    private Integer successCount;
    private Integer failureCount;
    private Long totalLatencyMs;
    private Integer totalInputTokens;
    private Integer totalOutputTokens;
    private String lastIp;
    private LocalDateTime lastReportedAt;
}