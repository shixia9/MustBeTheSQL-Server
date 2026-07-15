package com.sql.logic.admin.controller;

import com.sql.logic.engine.common.dubbo.AdminDataDTOs;
import com.sql.logic.engine.common.dubbo.AdminDataService;
import com.sql.logic.engine.common.response.Result;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Admin LLM monitoring — paginated system/user/config-level metrics.
 */
@RestController
@RequestMapping("/api/v1/admin/llm")
public class AdminLlmController {

    @DubboReference
    private AdminDataService adminDataService;

    public AdminLlmController() {}

    /** General LLM metrics list. */
    @GetMapping("/metrics")
    public Result<AdminDataDTOs.PageResult<Map<String, Object>>> getMetrics(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        AdminDataDTOs.PageResult<AdminDataDTOs.LlmMetricDTO> result =
                adminDataService.getLlmMetrics(page, size, keyword);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AdminDataDTOs.LlmMetricDTO m : result.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configId", m.getConfigId());
            row.put("configName", m.getConfigName());
            row.put("userId", m.getUserId());
            row.put("totalCalls", m.getTotalCalls());
            row.put("successCount", m.getSuccessCount());
            row.put("failureCount", m.getFailureCount());
            row.put("successRate", m.getSuccessRate());
            row.put("avgLatencyMs", m.getAvgLatencyMs());
            row.put("totalInputTokens", m.getTotalInputTokens());
            row.put("totalOutputTokens", m.getTotalOutputTokens());
            row.put("lastIp", m.getLastIp());
            rows.add(row);
        }
        AdminDataDTOs.PageResult<Map<String, Object>> pr =
                new AdminDataDTOs.PageResult<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
        return Result.success(pr);
    }

    /** System LLM: platform-provided LLM usage with user info, IP, and token consumption. */
    @GetMapping("/metrics/system")
    public Result<AdminDataDTOs.PageResult<Map<String, Object>>> getSystemMetrics(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        AdminDataDTOs.PageResult<AdminDataDTOs.SystemLlmMetricDTO> result =
                adminDataService.getSystemLlmMetrics(page, size, keyword);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AdminDataDTOs.SystemLlmMetricDTO m : result.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configId", m.getConfigId());
            row.put("configName", m.getConfigName());
            row.put("userId", m.getUserId());
            row.put("username", m.getUsername());
            row.put("userEmail", m.getUserEmail());
            row.put("userStatus", m.getUserStatus());
            row.put("lastIp", m.getLastIp());
            row.put("totalCalls", m.getTotalCalls());
            row.put("successCount", m.getSuccessCount());
            row.put("failureCount", m.getFailureCount());
            row.put("successRate", m.getSuccessRate());
            row.put("avgLatencyMs", m.getAvgLatencyMs());
            row.put("totalTokens", m.getTotalTokens());
            rows.add(row);
        }
        AdminDataDTOs.PageResult<Map<String, Object>> pr =
                new AdminDataDTOs.PageResult<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
        return Result.success(pr);
    }

    /** User LLM: user-owned configs with masked API keys and provider/model details. */
    @GetMapping("/metrics/users")
    public Result<AdminDataDTOs.PageResult<Map<String, Object>>> getUserMetrics(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        AdminDataDTOs.PageResult<AdminDataDTOs.UserLlmMetricDTO> result =
                adminDataService.getUserLlmMetrics(page, size, keyword);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AdminDataDTOs.UserLlmMetricDTO m : result.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configId", m.getConfigId());
            row.put("configName", m.getConfigName());
            row.put("userId", m.getUserId());
            row.put("username", m.getUsername());
            row.put("userEmail", m.getUserEmail());
            row.put("apiKeyMasked", m.getApiKeyMasked());
            row.put("providerType", m.getProviderType());
            row.put("modelName", m.getModelName());
            row.put("baseUrl", m.getBaseUrl());
            row.put("configStatus", m.getConfigStatus());
            row.put("totalCalls", m.getTotalCalls());
            row.put("successCount", m.getSuccessCount());
            row.put("failureCount", m.getFailureCount());
            row.put("successRate", m.getSuccessRate());
            row.put("avgLatencyMs", m.getAvgLatencyMs());
            row.put("totalTokens", m.getTotalTokens());
            rows.add(row);
        }
        AdminDataDTOs.PageResult<Map<String, Object>> pr =
                new AdminDataDTOs.PageResult<>(rows, result.getTotal(), result.getCurrent(), result.getSize());
        return Result.success(pr);
    }
}
