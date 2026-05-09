package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.SqlConsoleAppService;
import com.sql.logic.engine.trigger.http.dto.SqlConsoleExecuteRequest;
import com.sql.logic.engine.trigger.http.dto.SqlConsoleExecuteResponse;
import com.sql.logic.engine.trigger.http.response.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspace/console")
public class SqlConsoleController {

    private final SqlConsoleAppService sqlConsoleAppService;

    public SqlConsoleController(SqlConsoleAppService sqlConsoleAppService) {
        this.sqlConsoleAppService = sqlConsoleAppService;
    }

    @PostMapping("/execute")
    public Result<SqlConsoleExecuteResponse> executeSql(@RequestBody SqlConsoleExecuteRequest request) {
        try {
            SqlConsoleExecuteResponse response = sqlConsoleAppService.execute(request);
            return Result.success(response);
        } catch (Exception e) {
            return Result.error(500, "SQL Execution failed: " + e.getMessage());
        }
    }
}