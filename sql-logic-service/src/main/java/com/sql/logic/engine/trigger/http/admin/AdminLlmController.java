package com.sql.logic.engine.trigger.http.admin;

import com.sql.logic.engine.application.service.AdminUserAppService;
import com.sql.logic.engine.common.response.Result;
import com.sql.logic.engine.infrastructure.dao.LlmCallMetricsDao;
import com.sql.logic.engine.infrastructure.po.LlmCallMetrics;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/llm")
public class AdminLlmController {

    private final LlmCallMetricsDao llmCallMetricsDao;

    public AdminLlmController(LlmCallMetricsDao llmCallMetricsDao) {
        this.llmCallMetricsDao = llmCallMetricsDao;
    }

    /** Per-config call volume, success rate, avg latency. */
    @GetMapping("/metrics")
    public Result<List<Map<String, Object>>> getMetrics() {
        List<LlmCallMetrics> metrics = llmCallMetricsDao.selectList(null);
        List<Map<String, Object>> result = new ArrayList<>();
        for (LlmCallMetrics m : metrics) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configId", m.getConfigId());
            row.put("userId", m.getUserId());
            row.put("windowStart", m.getWindowStart());
            int total = m.getSuccessCount() + m.getFailureCount();
            row.put("totalCalls", total);
            row.put("successCount", m.getSuccessCount());
            row.put("failureCount", m.getFailureCount());
            row.put("successRate", total > 0 ? (double) m.getSuccessCount() / total : 0);
            row.put("avgLatencyMs", total > 0 ? m.getTotalLatencyMs() / total : 0);
            row.put("totalInputTokens", m.getTotalInputTokens());
            row.put("totalOutputTokens", m.getTotalOutputTokens());
            result.add(row);
        }
        return Result.success(result);
    }
}
