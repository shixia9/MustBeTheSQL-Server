package com.sql.logic.admin.controller;

import com.sql.logic.engine.common.dubbo.AdminDataDTOs;
import com.sql.logic.engine.common.dubbo.AdminDataService;
import com.sql.logic.engine.common.response.Result;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/llm")
public class AdminLlmController {

    @DubboReference
    private AdminDataService adminDataService;

    public AdminLlmController() {}

    @GetMapping("/metrics")
    public Result<List<Map<String, Object>>> getMetrics() {
        List<AdminDataDTOs.LlmMetricDTO> metrics = adminDataService.getLlmMetrics();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AdminDataDTOs.LlmMetricDTO m : metrics) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configId", m.getConfigId());
            row.put("configName", m.getConfigName());
            row.put("userId", m.getUserId());
            row.put("windowStart", m.getWindowStart());
            row.put("totalCalls", m.getTotalCalls());
            row.put("successCount", m.getSuccessCount());
            row.put("failureCount", m.getFailureCount());
            row.put("successRate", m.getSuccessRate());
            row.put("avgLatencyMs", m.getAvgLatencyMs());
            row.put("totalInputTokens", m.getTotalInputTokens());
            row.put("totalOutputTokens", m.getTotalOutputTokens());
            result.add(row);
        }
        return Result.success(result);
    }
}
