package com.sql.logic.engine.trigger.http;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.application.service.QueryHistoryAppService;
import com.sql.logic.engine.infrastructure.po.QueryHistory;
import com.sql.logic.engine.trigger.http.response.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
@SaCheckLogin
public class QueryHistoryController {

    private final QueryHistoryAppService queryHistoryAppService;

    public QueryHistoryController(QueryHistoryAppService queryHistoryAppService) {
        this.queryHistoryAppService = queryHistoryAppService;
    }

    @GetMapping("/user/{userId}")
    public Result<Page<QueryHistory>> getUserHistory(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dbType,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        // Verify the requesting user matches the path userId
        Long loginUserId = StpUtil.getLoginIdAsLong();
        if (!loginUserId.equals(userId)) {
            return Result.error(403, "Access denied");
        }
        return Result.success(queryHistoryAppService.getUserHistory(userId, page, size, keyword, dbType, model, startDate, endDate));
    }

    @DeleteMapping("/{id}")
    public Result<Boolean> deleteHistory(@PathVariable Long id) {
        return Result.success(queryHistoryAppService.deleteHistory(id));
    }

    @GetMapping("/{id}/lineage")
    public Result<List<QueryHistory>> getHistoryLineage(@PathVariable Long id) {
        return Result.success(queryHistoryAppService.getHistoryLineage(id));
    }
}