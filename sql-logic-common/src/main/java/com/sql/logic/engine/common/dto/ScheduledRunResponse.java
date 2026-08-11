package com.sql.logic.engine.common.dto;

import lombok.Data;

@Data
public class ScheduledRunResponse {
    private Long id;            // run id
    private Long taskId;
    private String status;      // running / success / failed / timeout
    private String startedAt;   // formatted yyyy-MM-dd HH:mm:ss
    private String finishedAt;
    private String resultSummary;
    private String errorMessage;
    private String outputConversationId;
    private Integer attempt;
    private String createTime;
}
