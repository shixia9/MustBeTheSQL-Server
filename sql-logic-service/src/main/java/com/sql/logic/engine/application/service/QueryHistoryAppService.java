package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.infrastructure.dao.QueryHistoryDao;
import com.sql.logic.engine.infrastructure.po.QueryHistory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

@Service
public class QueryHistoryAppService {

    private final QueryHistoryDao queryHistoryDao;

    public QueryHistoryAppService(QueryHistoryDao queryHistoryDao) {
        this.queryHistoryDao = queryHistoryDao;
    }

    public void recordGeneration(Long userId, String prompt, Long connectionId, String databaseName, String generatedSql, String modelName, Integer tokens, Long parentId, Long llmConfigId) {
        QueryHistory history = new QueryHistory();
        history.setUserId(userId);
        history.setPrompt(prompt);
        history.setConnectionId(connectionId);
        history.setDatabaseName(databaseName);
        history.setGeneratedSql(generatedSql);
        history.setModelName(modelName);
        history.setTokens(tokens);
        history.setParentId(parentId);
        history.setLlmConfigId(llmConfigId);
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

    public void recordReRunExecution(Long userId, Long parentHistoryId, Long latency, Integer rowCount) {
        QueryHistory parentHistory = queryHistoryDao.selectById(parentHistoryId);
        if (parentHistory != null) {
            QueryHistory newHistory = new QueryHistory();
            newHistory.setUserId(userId);
            newHistory.setPrompt(parentHistory.getPrompt());
            newHistory.setConnectionId(parentHistory.getConnectionId());
            newHistory.setDatabaseName(parentHistory.getDatabaseName());
            newHistory.setGeneratedSql(parentHistory.getGeneratedSql());
            newHistory.setModelName(parentHistory.getModelName());
            newHistory.setTokens(0);
            newHistory.setCost(BigDecimal.ZERO);
            newHistory.setParentId(parentHistoryId);
            newHistory.setCreateTime(new Date());
            newHistory.setExecuteTime(new Date());
            newHistory.setExecuteLatency(latency);
            newHistory.setRowCount(rowCount);
            queryHistoryDao.insert(newHistory);
        }
    }

    public Page<QueryHistory> getUserHistory(Long userId, Integer page, Integer size, String keyword, String dbType, String model, String startDate, String endDate) {
        QueryWrapper<QueryHistory> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);

        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like("prompt", keyword).or().like("generated_sql", keyword));
        }
        if (StringUtils.hasText(dbType) && !"All DB Types".equalsIgnoreCase(dbType)) {
            wrapper.like("database_name", dbType);
        }
        if (StringUtils.hasText(model) && !"All Models".equalsIgnoreCase(model)) {
            wrapper.like("model_name", model);
        }
        if (StringUtils.hasText(startDate)) {
            wrapper.ge("create_time", startDate);
        }
        if (StringUtils.hasText(endDate)) {
            wrapper.le("create_time", endDate);
        }

        wrapper.orderByDesc("create_time");
        return queryHistoryDao.selectPage(new Page<>(page, size), wrapper);
    }

    public Boolean deleteHistory(Long id) {
        return queryHistoryDao.deleteById(id) > 0;
    }

    public List<QueryHistory> getHistoryLineage(Long id) {
        List<QueryHistory> lineage = new ArrayList<>();
        QueryHistory current = queryHistoryDao.selectById(id);
        
        // Find ancestors
        while (current != null) {
            lineage.add(current);
            if (current.getParentId() != null) {
                current = queryHistoryDao.selectById(current.getParentId());
            } else {
                break;
            }
        }
        
        Collections.reverse(lineage);
        
        // Find descendants (direct children for now)
        List<QueryHistory> children = queryHistoryDao.selectList(new QueryWrapper<QueryHistory>().eq("parent_id", id).orderByAsc("create_time"));
        lineage.addAll(children);
        
        return lineage;
    }
}
