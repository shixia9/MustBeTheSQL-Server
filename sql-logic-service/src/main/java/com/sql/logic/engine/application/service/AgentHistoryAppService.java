package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.infrastructure.dao.AgentExecutionDao;
import com.sql.logic.engine.infrastructure.dao.AgentExecutionStepDao;
import com.sql.logic.engine.infrastructure.po.AgentExecution;
import com.sql.logic.engine.infrastructure.po.AgentExecutionStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentHistoryAppService {

    private static final Logger log = LoggerFactory.getLogger(AgentHistoryAppService.class);

    private final AgentExecutionDao agentExecutionDao;
    private final AgentExecutionStepDao agentExecutionStepDao;

    public AgentHistoryAppService(AgentExecutionDao agentExecutionDao, AgentExecutionStepDao agentExecutionStepDao) {
        this.agentExecutionDao = agentExecutionDao;
        this.agentExecutionStepDao = agentExecutionStepDao;
    }

    public AgentExecution saveExecution(AgentExecution execution) {
        agentExecutionDao.insert(execution);
        return execution;
    }

    public void saveStep(AgentExecutionStep step) {
        agentExecutionStepDao.insert(step);
    }

    public void saveSteps(List<AgentExecutionStep> steps) {
        for (AgentExecutionStep step : steps) {
            agentExecutionStepDao.insert(step);
        }
    }

    public Page<AgentExecution> listExecutions(Long userId, int page, int size) {
        Page<AgentExecution> p = new Page<>(page, size);
        QueryWrapper<AgentExecution> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        qw.orderByDesc("create_time");
        return agentExecutionDao.selectPage(p, qw);
    }

    /**
     * Page the user's agent executions, optionally filtering by a keyword that
     * matches the AI-generated {@code summary} OR the original {@code input},
     * and optionally scoped to a workspace.
     * The keyword is matched with a LIKE '%kw%' on both columns (OR-joined).
     */
    public Page<AgentExecution> listExecutions(Long userId, int page, int size, String keyword) {
        return listExecutions(userId, page, size, keyword, null);
    }

    /**
     * Same as above but with optional workspace_id scoping.
     * When {@code workspaceId} is null, falls back to user-level isolation (backward compat).
     */
    public Page<AgentExecution> listExecutions(Long userId, int page, int size, String keyword, Long workspaceId) {
        Page<AgentExecution> p = new Page<>(page, size);
        QueryWrapper<AgentExecution> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        if (workspaceId != null) {
            qw.eq("workspace_id", workspaceId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like("summary", kw).or().like("input", kw));
        }
        qw.orderByDesc("create_time");
        return agentExecutionDao.selectPage(p, qw);
    }

    public AgentExecution getExecution(Long id) {
        return agentExecutionDao.selectById(id);
    }

    public List<AgentExecutionStep> getSteps(Long executionId) {
        QueryWrapper<AgentExecutionStep> qw = new QueryWrapper<>();
        qw.eq("execution_id", executionId);
        qw.orderByAsc("sequence_no");
        return agentExecutionStepDao.selectList(qw);
    }

    public void deleteExecution(Long id) {
        agentExecutionStepDao.delete(new QueryWrapper<AgentExecutionStep>().eq("execution_id", id));
        agentExecutionDao.deleteById(id);
    }
}
