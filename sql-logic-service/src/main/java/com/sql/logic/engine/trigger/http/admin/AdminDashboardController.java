package com.sql.logic.engine.trigger.http.admin;

import com.sql.logic.engine.application.service.AdminUserAppService;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.infrastructure.dao.*;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminDashboardController {

    private final AdminUserAppService adminUserAppService;
    private final AgentExecutionDao agentExecutionDao;
    private final LlmCallMetricsDao llmCallMetricsDao;
    private final UserInfoDao userInfoDao;

    public AdminDashboardController(AdminUserAppService adminUserAppService,
                                    AgentExecutionDao agentExecutionDao,
                                    LlmCallMetricsDao llmCallMetricsDao,
                                    UserInfoDao userInfoDao) {
        this.adminUserAppService = adminUserAppService;
        this.agentExecutionDao = agentExecutionDao;
        this.llmCallMetricsDao = llmCallMetricsDao;
        this.userInfoDao = userInfoDao;
    }

    /** Aggregate dashboard stats. */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", userInfoDao.selectCount(null));
        stats.put("totalAdmins", adminUserAppService.listAdmins().size());
        stats.put("totalExecutions", agentExecutionDao.selectCount(null));
        stats.put("activeToday", estimateDAU());
        stats.put("timestamp", System.currentTimeMillis());
        return Result.success(stats);
    }

    private long estimateDAU() {
        // Crude estimate: executions within the last 24 hours with distinct user_ids
        var today = java.time.LocalDateTime.now().minusHours(24);
        var qw = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.sql.logic.engine.infrastructure.po.AgentExecution>()
                .ge("create_time", today)
                .select("DISTINCT user_id");
        return agentExecutionDao.selectList(qw).size();
    }
}
