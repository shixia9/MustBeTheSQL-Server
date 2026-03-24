package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.SQLExecuteAppService;
import com.sql.logic.engine.trigger.http.dto.SqlExecuteRequest;
import com.sql.logic.engine.trigger.http.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sql")
public class SQLExecuteController {

    private final SQLExecuteAppService sqlExecuteAppService;

    public SQLExecuteController(SQLExecuteAppService sqlExecuteAppService) {
        this.sqlExecuteAppService = sqlExecuteAppService;
    }

    @PostMapping("/execute")
    public Result<List<Map<String, Object>>> executeSql(@RequestBody SqlExecuteRequest request) {
        try {
            List<Map<String, Object>> res = sqlExecuteAppService.executeQuery(request.getSql());
            return Result.success(res);
        } catch (Exception e) {
            return Result.error(400, e.getMessage());
        }
    }
}
