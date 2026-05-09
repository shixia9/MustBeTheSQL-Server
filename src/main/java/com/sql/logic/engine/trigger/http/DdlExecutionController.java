package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.DdlExecutionAppService;
import com.sql.logic.engine.trigger.http.dto.DdlExecuteRequest;
import com.sql.logic.engine.trigger.http.dto.DdlExecuteResponse;
import com.sql.logic.engine.trigger.http.response.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspace/ddl")
public class DdlExecutionController {

    private final DdlExecutionAppService ddlExecutionAppService;

    public DdlExecutionController(DdlExecutionAppService ddlExecutionAppService) {
        this.ddlExecutionAppService = ddlExecutionAppService;
    }

    @PostMapping("/execute")
    public Result<DdlExecuteResponse> executeDdl(@RequestBody DdlExecuteRequest request) {
        try {
            DdlExecuteResponse response = ddlExecutionAppService.execute(request);
            return Result.success(response);
        } catch (Exception e) {
            return Result.error(500, "DDL Execution failed: " + e.getMessage());
        }
    }
}