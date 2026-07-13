package com.sql.logic.engine.common.dubbo;

import java.io.Serializable;
import java.util.*;

/**
 * DTOs for the Dubbo AdminDataService.
 * Must be Serializable for Dubbo transport.
 */
public class AdminDataDTOs {

    private AdminDataDTOs() {}

    public static class UserDTO implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long id;
        private String username;
        private String email;
        private int status;
        private int tokenQuota;
        private Date createTime;
        private boolean admin;
        private String adminRole;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
        public int getTokenQuota() { return tokenQuota; }
        public void setTokenQuota(int tokenQuota) { this.tokenQuota = tokenQuota; }
        public Date getCreateTime() { return createTime; }
        public void setCreateTime(Date createTime) { this.createTime = createTime; }
        public boolean isAdmin() { return admin; }
        public void setAdmin(boolean admin) { this.admin = admin; }
        public String getAdminRole() { return adminRole; }
        public void setAdminRole(String adminRole) { this.adminRole = adminRole; }
    }

    public static class LlmMetricDTO implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long configId;
        private String configName;
        private Long userId;
        private java.time.LocalDateTime windowStart;
        private int totalCalls;
        private int successCount;
        private int failureCount;
        private double successRate;
        private long avgLatencyMs;
        private long totalInputTokens;
        private long totalOutputTokens;

        public Long getConfigId() { return configId; }
        public void setConfigId(Long configId) { this.configId = configId; }
        public String getConfigName() { return configName; }
        public void setConfigName(String configName) { this.configName = configName; }
        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public java.time.LocalDateTime getWindowStart() { return windowStart; }
        public void setWindowStart(java.time.LocalDateTime windowStart) { this.windowStart = windowStart; }
        public int getTotalCalls() { return totalCalls; }
        public void setTotalCalls(int totalCalls) { this.totalCalls = totalCalls; }
        public int getSuccessCount() { return successCount; }
        public void setSuccessCount(int successCount) { this.successCount = successCount; }
        public int getFailureCount() { return failureCount; }
        public void setFailureCount(int failureCount) { this.failureCount = failureCount; }
        public double getSuccessRate() { return successRate; }
        public void setSuccessRate(double successRate) { this.successRate = successRate; }
        public long getAvgLatencyMs() { return avgLatencyMs; }
        public void setAvgLatencyMs(long avgLatencyMs) { this.avgLatencyMs = avgLatencyMs; }
        public long getTotalInputTokens() { return totalInputTokens; }
        public void setTotalInputTokens(long totalInputTokens) { this.totalInputTokens = totalInputTokens; }
        public long getTotalOutputTokens() { return totalOutputTokens; }
        public void setTotalOutputTokens(long totalOutputTokens) { this.totalOutputTokens = totalOutputTokens; }
    }

    public static class PageResult<T> implements Serializable {
        private static final long serialVersionUID = 1L;
        private List<T> records;
        private long total;
        private long current;
        private long size;

        public PageResult() {}
        public PageResult(List<T> records, long total, long current, long size) {
            this.records = records; this.total = total; this.current = current; this.size = size;
        }
        public List<T> getRecords() { return records; }
        public void setRecords(List<T> records) { this.records = records; }
        public long getTotal() { return total; }
        public void setTotal(long total) { this.total = total; }
        public long getCurrent() { return current; }
        public void setCurrent(long current) { this.current = current; }
        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }
    }

    public static class DashboardStats implements Serializable {
        private static final long serialVersionUID = 1L;
        private long totalUsers;
        private long totalAdmins;
        private long totalExecutions;
        private long activeToday;

        public long getTotalUsers() { return totalUsers; }
        public void setTotalUsers(long totalUsers) { this.totalUsers = totalUsers; }
        public long getTotalAdmins() { return totalAdmins; }
        public void setTotalAdmins(long totalAdmins) { this.totalAdmins = totalAdmins; }
        public long getTotalExecutions() { return totalExecutions; }
        public void setTotalExecutions(long totalExecutions) { this.totalExecutions = totalExecutions; }
        public long getActiveToday() { return activeToday; }
        public void setActiveToday(long activeToday) { this.activeToday = activeToday; }
    }
}
