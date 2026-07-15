package com.sql.logic.engine.common.dubbo;

import java.util.List;
import java.util.Map;

/**
 * Dubbo service for admin module to query business data from the service module.
 * All methods are read-only / admin-operational — the admin module calls this
 * instead of accessing the service database directly.
 */
public interface AdminDataService {

    AdminDataDTOs.PageResult<AdminDataDTOs.UserDTO> listUsers(int page, int size, String keyword, String status);

    List<AdminDataDTOs.LlmMetricDTO> getLlmMetrics();

    AdminDataDTOs.DashboardStats getDashboardStats();

    void toggleUserStatus(Long userId, int newStatus);

    void adjustQuota(Long userId, long quota);

    /** Paginated + searchable LLM metrics for admin monitoring. */
    AdminDataDTOs.PageResult<AdminDataDTOs.LlmMetricDTO> getLlmMetrics(int page, int size, String keyword);

    /** System LLM usage — user info, IP, per-user token consumption. */
    AdminDataDTOs.PageResult<AdminDataDTOs.SystemLlmMetricDTO> getSystemLlmMetrics(int page, int size, String keyword);

    /** User LLM configs — masked API keys, provider/model details. */
    AdminDataDTOs.PageResult<AdminDataDTOs.UserLlmMetricDTO> getUserLlmMetrics(int page, int size, String keyword);
}
