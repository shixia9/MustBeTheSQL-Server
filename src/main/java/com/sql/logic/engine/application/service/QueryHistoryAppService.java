package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.infrastructure.dao.QueryHistoryDao;
import com.sql.logic.engine.infrastructure.po.QueryHistory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Service
public class QueryHistoryAppService {

    private final QueryHistoryDao queryHistoryDao;

    public QueryHistoryAppService(QueryHistoryDao queryHistoryDao) {
        this.queryHistoryDao = queryHistoryDao;
    }

    public void recordGeneration(Long userId, String prompt, Long connectionId, String databaseName, String generatedSql, String modelName, Integer tokens) {
        QueryHistory history = new QueryHistory();
        history.setUserId(userId);
        history.setPrompt(prompt);
        history.setConnectionId(connectionId);
        history.setDatabaseName(databaseName);
        history.setGeneratedSql(generatedSql);
        history.setModelName(modelName);
        history.setTokens(tokens);
        // Cost estimation: $2 per 1M tokens (0.000002 per token)
        if (tokens != null) {
            history.setCost(new BigDecimal(tokens).multiply(new BigDecimal("0.000002")));
        } else {
            history.setCost(BigDecimal.ZERO);
        }
        history.setCreateTime(new Date());
        
        queryHistoryDao.insert(history);
    }

    public void recordExecution(Long userId, String sql, Long latency, Integer rowCount) {
        // Find the latest generated SQL record for this user
        QueryWrapper<QueryHistory> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
               .eq("generated_sql", sql)
               .orderByDesc("create_time")
               .last("LIMIT 1");
               
        QueryHistory latestHistory = queryHistoryDao.selectOne(wrapper);
        if (latestHistory != null) {
            latestHistory.setExecuteLatency(latency);
            latestHistory.setRowCount(rowCount);
            latestHistory.setExecuteTime(new Date());
            queryHistoryDao.updateById(latestHistory);
        }
    }

    public List<QueryHistory> getUserHistory(Long userId) {
        QueryWrapper<QueryHistory> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).orderByDesc("create_time");
        return queryHistoryDao.selectList(wrapper);
    }
}
