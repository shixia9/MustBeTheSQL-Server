package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.application.service.QueryHistoryAppService;
import com.sql.logic.engine.infrastructure.po.QueryHistory;
import com.sql.logic.engine.trigger.http.response.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class QueryHistoryController {

    private final QueryHistoryAppService queryHistoryAppService;

    public QueryHistoryController(QueryHistoryAppService queryHistoryAppService) {
        this.queryHistoryAppService = queryHistoryAppService;
    }

    @GetMapping("/user/{userId}")
    public Result<List<QueryHistory>> getUserHistory(@PathVariable Long userId) {
        return Result.success(queryHistoryAppService.getUserHistory(userId));
    }
}
