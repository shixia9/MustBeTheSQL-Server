package com.sql.logic.engine.infrastructure.dubbo;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.common.dubbo.AdminDataDTOs;
import com.sql.logic.engine.common.dubbo.AdminDataService;
import com.sql.logic.engine.infrastructure.dao.*;
import com.sql.logic.engine.infrastructure.po.*;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

@DubboService
public class AdminDataProvider implements AdminDataService {

    private static final Logger log = LoggerFactory.getLogger(AdminDataProvider.class);

    private final UserInfoDao userInfoDao;
    private final LlmCallMetricsDao llmCallMetricsDao;
    private final AgentExecutionDao agentExecutionDao;
    private final UserLlmConfigDao userLlmConfigDao;

    public AdminDataProvider(UserInfoDao userInfoDao,
                             LlmCallMetricsDao llmCallMetricsDao,
                             AgentExecutionDao agentExecutionDao,
                             UserLlmConfigDao userLlmConfigDao) {
        this.userInfoDao = userInfoDao;
        this.llmCallMetricsDao = llmCallMetricsDao;
        this.agentExecutionDao = agentExecutionDao;
        this.userLlmConfigDao = userLlmConfigDao;
    }

    @Override
    public AdminDataDTOs.PageResult<AdminDataDTOs.UserDTO> listUsers(int page, int size, String keyword, String status) {
        Page<UserInfo> p = new Page<>(page, size);
        QueryWrapper<UserInfo> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like("username", keyword.trim()).or().like("email", keyword.trim()));
        }
        if (status != null && !status.isBlank()) {
            qw.eq("status", Integer.parseInt(status));
        }
        qw.orderByDesc("id");
        Page<UserInfo> userPage = userInfoDao.selectPage(p, qw);

        List<AdminDataDTOs.UserDTO> dtos = userPage.getRecords().stream().map(u -> {
            AdminDataDTOs.UserDTO dto = new AdminDataDTOs.UserDTO();
            dto.setId(u.getId());
            dto.setUsername(u.getUsername());
            dto.setEmail(u.getEmail());
            dto.setStatus(u.getStatus() != null ? u.getStatus() : 1);
            dto.setTokenQuota(u.getTokenQuota() != null ? u.getTokenQuota() : 0);
            dto.setCreateTime(u.getCreateTime());
            return dto;
        }).collect(Collectors.toList());

        return new AdminDataDTOs.PageResult<>(dtos, userPage.getTotal(), userPage.getCurrent(), userPage.getSize());
    }

    @Override
    public List<AdminDataDTOs.LlmMetricDTO> getLlmMetrics() {
        List<LlmCallMetrics> metrics = llmCallMetricsDao.selectList(null);
        // Batch-load config names for readability in the admin dashboard
        Map<Long, String> configNames = new HashMap<>();
        for (LlmCallMetrics m : metrics) {
            if (m.getConfigId() != null && !configNames.containsKey(m.getConfigId())) {
                UserLlmConfig cfg = userLlmConfigDao.selectById(m.getConfigId());
                if (cfg != null && cfg.getConfigName() != null) {
                    configNames.put(m.getConfigId(), cfg.getConfigName());
                }
            }
        }
        return metrics.stream().map(m -> {
            AdminDataDTOs.LlmMetricDTO dto = new AdminDataDTOs.LlmMetricDTO();
            dto.setConfigId(m.getConfigId());
            dto.setConfigName(configNames.getOrDefault(m.getConfigId(), "Config #" + m.getConfigId()));
            dto.setUserId(m.getUserId());
            dto.setWindowStart(m.getWindowStart());
            int total = m.getSuccessCount() + m.getFailureCount();
            dto.setTotalCalls(total);
            dto.setSuccessCount(m.getSuccessCount());
            dto.setFailureCount(m.getFailureCount());
            dto.setSuccessRate(total > 0 ? (double) m.getSuccessCount() / total : 0);
            dto.setAvgLatencyMs(total > 0 ? m.getTotalLatencyMs() / total : 0);
            dto.setTotalInputTokens(m.getTotalInputTokens());
            dto.setTotalOutputTokens(m.getTotalOutputTokens());
            dto.setLastIp(m.getLastIp());
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    public AdminDataDTOs.DashboardStats getDashboardStats() {
        AdminDataDTOs.DashboardStats stats = new AdminDataDTOs.DashboardStats();
        stats.setTotalUsers(userInfoDao.selectCount(null));
        stats.setTotalExecutions(agentExecutionDao.selectCount(null));

        var today = java.util.Date.from(java.time.LocalDateTime.now().minusHours(24)
                .atZone(java.time.ZoneId.systemDefault()).toInstant());
        var qw = new QueryWrapper<AgentExecution>()
                .ge("create_time", today)
                .select("DISTINCT user_id");
        stats.setActiveToday(agentExecutionDao.selectList(qw).size());
        return stats;
    }

    @Override
    public void toggleUserStatus(Long userId, int newStatus) {
        UserInfo u = new UserInfo();
        u.setId(userId);
        u.setStatus(newStatus);
        userInfoDao.updateById(u);
        log.info("[AdminDataProvider] User {} status -> {}", userId, newStatus);
    }

    @Override
    public void adjustQuota(Long userId, long quota) {
        UserInfo u = new UserInfo();
        u.setId(userId);
        u.setTokenQuota((int) quota);
        userInfoDao.updateById(u);
        log.info("[AdminDataProvider] User {} quota -> {}", userId, quota);
    }

    @Override
    public AdminDataDTOs.PageResult<AdminDataDTOs.LlmMetricDTO> getLlmMetrics(int page, int size, String keyword) {
        Page<LlmCallMetrics> p = new Page<>(page, size);
        QueryWrapper<LlmCallMetrics> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.like("config_id", keyword); // keyword matches configId for now
        }
        qw.orderByDesc("window_start");
        Page<LlmCallMetrics> result = llmCallMetricsDao.selectPage(p, qw);

        Map<Long, String> configNames = new HashMap<>();
        for (LlmCallMetrics m : result.getRecords()) {
            if (m.getConfigId() != null && !configNames.containsKey(m.getConfigId())) {
                UserLlmConfig cfg = userLlmConfigDao.selectById(m.getConfigId());
                if (cfg != null && cfg.getConfigName() != null) {
                    configNames.put(m.getConfigId(), cfg.getConfigName());
                }
            }
        }
        List<AdminDataDTOs.LlmMetricDTO> dtos = result.getRecords().stream().map(m -> {
            AdminDataDTOs.LlmMetricDTO dto = new AdminDataDTOs.LlmMetricDTO();
            dto.setConfigId(m.getConfigId());
            dto.setConfigName(configNames.getOrDefault(m.getConfigId(), "Config #" + m.getConfigId()));
            dto.setUserId(m.getUserId());
            dto.setSuccessRate(m.getSuccessCount() + m.getFailureCount() > 0
                    ? (double) m.getSuccessCount() / (m.getSuccessCount() + m.getFailureCount()) : 0);
            dto.setAvgLatencyMs(m.getSuccessCount() + m.getFailureCount() > 0
                    ? m.getTotalLatencyMs() / (m.getSuccessCount() + m.getFailureCount()) : 0);
            dto.setTotalCalls(m.getSuccessCount() + m.getFailureCount());
            dto.setSuccessCount(m.getSuccessCount());
            dto.setFailureCount(m.getFailureCount());
            dto.setTotalInputTokens(m.getTotalInputTokens());
            dto.setTotalOutputTokens(m.getTotalOutputTokens());
            dto.setLastIp(m.getLastIp());
            return dto;
        }).collect(Collectors.toList());
        return new AdminDataDTOs.PageResult<>(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AdminDataDTOs.PageResult<AdminDataDTOs.SystemLlmMetricDTO> getSystemLlmMetrics(int page, int size, String keyword) {
        Page<LlmCallMetrics> p = new Page<>(page, size);
        QueryWrapper<LlmCallMetrics> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.eq("config_id", safeParseLong(keyword)));
        }
        qw.orderByDesc("window_start");
        Page<LlmCallMetrics> result = llmCallMetricsDao.selectPage(p, qw);

        Map<Long, UserInfo> userCache = new HashMap<>();
        Map<Long, String> configNames = new HashMap<>();
        List<AdminDataDTOs.SystemLlmMetricDTO> dtos = new ArrayList<>();
        for (LlmCallMetrics m : result.getRecords()) {
            UserInfo u = userCache.computeIfAbsent(m.getUserId(), uid -> userInfoDao.selectById(uid));
            if (!configNames.containsKey(m.getConfigId())) {
                UserLlmConfig cfg = userLlmConfigDao.selectById(m.getConfigId());
                if (cfg != null && cfg.getConfigName() != null) {
                    configNames.put(m.getConfigId(), cfg.getConfigName());
                }
            }
            AdminDataDTOs.SystemLlmMetricDTO dto = new AdminDataDTOs.SystemLlmMetricDTO();
            dto.setConfigId(m.getConfigId());
            dto.setConfigName(configNames.getOrDefault(m.getConfigId(), "Config #" + m.getConfigId()));
            dto.setUserId(m.getUserId());
            dto.setUsername(u != null ? u.getUsername() : null);
            dto.setUserEmail(u != null ? u.getEmail() : null);
            dto.setUserStatus(u != null && u.getStatus() != null ? u.getStatus() : 1);
            dto.setLastIp(m.getLastIp());
            dto.setTotalCalls(m.getSuccessCount() + m.getFailureCount());
            dto.setSuccessCount(m.getSuccessCount());
            dto.setFailureCount(m.getFailureCount());
            dto.setSuccessRate(m.getSuccessCount() + m.getFailureCount() > 0
                    ? (double) m.getSuccessCount() / (m.getSuccessCount() + m.getFailureCount()) : 0);
            dto.setAvgLatencyMs(m.getSuccessCount() + m.getFailureCount() > 0
                    ? m.getTotalLatencyMs() / (m.getSuccessCount() + m.getFailureCount()) : 0);
            dto.setTotalTokens((m.getTotalInputTokens() != null ? m.getTotalInputTokens() : 0L)
                    + (m.getTotalOutputTokens() != null ? m.getTotalOutputTokens() : 0L));
            dtos.add(dto);
        }
        return new AdminDataDTOs.PageResult<>(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public AdminDataDTOs.PageResult<AdminDataDTOs.UserLlmMetricDTO> getUserLlmMetrics(int page, int size, String keyword) {
        Page<LlmCallMetrics> p = new Page<>(page, size);
        QueryWrapper<LlmCallMetrics> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.eq("config_id", safeParseLong(keyword)));
        }
        qw.orderByDesc("window_start");
        Page<LlmCallMetrics> result = llmCallMetricsDao.selectPage(p, qw);

        Map<Long, UserInfo> userCache = new HashMap<>();
        Map<Long, UserLlmConfig> configCache = new HashMap<>();
        List<AdminDataDTOs.UserLlmMetricDTO> dtos = new ArrayList<>();
        for (LlmCallMetrics m : result.getRecords()) {
            UserInfo u = userCache.computeIfAbsent(m.getUserId(), uid -> userInfoDao.selectById(uid));
            UserLlmConfig cfg = configCache.computeIfAbsent(m.getConfigId(), cid -> userLlmConfigDao.selectById(cid));
            AdminDataDTOs.UserLlmMetricDTO dto = new AdminDataDTOs.UserLlmMetricDTO();
            dto.setConfigId(m.getConfigId());
            dto.setConfigName(cfg != null ? cfg.getConfigName() : "Config #" + m.getConfigId());
            dto.setUserId(m.getUserId());
            dto.setUsername(u != null ? u.getUsername() : null);
            dto.setUserEmail(u != null ? u.getEmail() : null);
            if (cfg != null && cfg.getApiKey() != null) {
                String key = cfg.getApiKey();
                dto.setApiKeyMasked(key.length() > 8 ? key.substring(0, 3) + "..." + key.substring(key.length() - 4) : "***");
            }
            dto.setProviderType(cfg != null ? cfg.getProviderType() : null);
            dto.setModelName(cfg != null ? cfg.getModelName() : null);
            dto.setBaseUrl(cfg != null ? cfg.getBaseUrl() : null);
            dto.setConfigStatus(cfg != null && cfg.getStatus() != null ? cfg.getStatus() : 0);
            dto.setTotalCalls(m.getSuccessCount() + m.getFailureCount());
            dto.setSuccessCount(m.getSuccessCount());
            dto.setFailureCount(m.getFailureCount());
            dto.setSuccessRate(m.getSuccessCount() + m.getFailureCount() > 0
                    ? (double) m.getSuccessCount() / (m.getSuccessCount() + m.getFailureCount()) : 0);
            dto.setAvgLatencyMs(m.getSuccessCount() + m.getFailureCount() > 0
                    ? m.getTotalLatencyMs() / (m.getSuccessCount() + m.getFailureCount()) : 0);
            dto.setTotalTokens((m.getTotalInputTokens() != null ? m.getTotalInputTokens() : 0L)
                    + (m.getTotalOutputTokens() != null ? m.getTotalOutputTokens() : 0L));
            dtos.add(dto);
        }
        return new AdminDataDTOs.PageResult<>(dtos, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private Long safeParseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }
}
