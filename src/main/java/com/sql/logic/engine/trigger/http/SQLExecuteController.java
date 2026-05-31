package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.sql.logic.engine.application.exception.BizException;
import com.sql.logic.engine.application.service.SQLExecuteAppService;
import com.sql.logic.engine.trigger.http.dto.SqlExecuteRequest;
import com.sql.logic.engine.trigger.http.dto.SqlExecuteResponse;
import com.sql.logic.engine.trigger.http.response.Result;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/sql")
@SaCheckLogin
public class SQLExecuteController {

    private final SQLExecuteAppService sqlExecuteAppService;

    public SQLExecuteController(SQLExecuteAppService sqlExecuteAppService) {
        this.sqlExecuteAppService = sqlExecuteAppService;
    }

    @PostMapping("/execute")
    public Result<?> executeSql(@RequestBody SqlExecuteRequest request) {
        try {
            SqlExecuteResponse result = sqlExecuteAppService.execute(request);
            return Result.success(result);
        } catch (BizException e) {
            if (e.getData() != null) {
                return Result.error(e.getCode(), e.getMessage(), e.getData());
            }
            return Result.error(e.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "Internal server error: " + e.getMessage());
        }
    }
}