package com.sql.logic.engine.common.dto;

import lombok.Data;

import java.util.List;

@Data
public class RunListResponse {
    private List<ScheduledRunResponse> runs;
    private Long total;

    public RunListResponse() {
    }

    public RunListResponse(List<ScheduledRunResponse> runs, Long total) {
        this.runs = runs;
        this.total = total;
    }
}
