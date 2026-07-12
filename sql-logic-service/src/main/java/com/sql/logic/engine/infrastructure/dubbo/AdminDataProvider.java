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

    public AdminDataProvider(UserInfoDao userInfoDao,
                             LlmCallMetricsDao llmCallMetricsDao,
                             AgentExecutionDao agentExecutionDao) {
        this.userInfoDao = userInfoDao;
        this.llmCallMetricsDao = llmCallMetricsDao;
        this.agentExecutionDao = agentExecutionDao;
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
        return metrics.stream().map(m -> {
            AdminDataDTOs.LlmMetricDTO dto = new AdminDataDTOs.LlmMetricDTO();
            dto.setConfigId(m.getConfigId());
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
}
