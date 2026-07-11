package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.infrastructure.dao.AgentExecutionDao;
import com.sql.logic.engine.infrastructure.dao.AgentExecutionStepDao;
import com.sql.logic.engine.infrastructure.dao.ConversationDao;
import com.sql.logic.engine.infrastructure.dao.ConversationDetailDao;
import com.sql.logic.engine.infrastructure.po.AgentExecution;
import com.sql.logic.engine.infrastructure.po.AgentExecutionStep;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class AgentHistoryAppService {

    private static final Logger log = LoggerFactory.getLogger(AgentHistoryAppService.class);

    // Remove the fixed-format DATE_FMT; use flexible parse below.

    private final AgentExecutionDao agentExecutionDao;
    private final AgentExecutionStepDao agentExecutionStepDao;
    private final ConversationDao conversationDao;
    private final ConversationDetailDao conversationDetailDao;

    public AgentHistoryAppService(AgentExecutionDao agentExecutionDao,
                                  AgentExecutionStepDao agentExecutionStepDao,
                                  ConversationDao conversationDao,
                                  ConversationDetailDao conversationDetailDao) {
        this.agentExecutionDao = agentExecutionDao;
        this.agentExecutionStepDao = agentExecutionStepDao;
        this.conversationDao = conversationDao;
        this.conversationDetailDao = conversationDetailDao;
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
        return listExecutions(userId, page, size, null, null, null, null, null);
    }

    public Page<AgentExecution> listExecutions(Long userId, int page, int size, String keyword) {
        return listExecutions(userId, page, size, keyword, null, null, null, null);
    }

    public Page<AgentExecution> listExecutions(Long userId, int page, int size, String keyword,
                                                Long workspaceId) {
        return listExecutions(userId, page, size, keyword, workspaceId, null, null, null);
    }

    public Page<AgentExecution> listExecutions(Long userId, int page, int size, String keyword,
                                                Long workspaceId, Long conversationId) {
        return listExecutions(userId, page, size, keyword, workspaceId, conversationId, null, null);
    }

    /**
     * Paginated query with optional keyword (matches summary or input), optional
     * workspace/conversation scoping, and optional date range filter.
     */
    public Page<AgentExecution> listExecutions(Long userId, int page, int size, String keyword,
                                                Long workspaceId, Long conversationId,
                                                String startDate, String endDate) {
        Page<AgentExecution> p = new Page<>(page, size);
        QueryWrapper<AgentExecution> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        if (workspaceId != null) {
            qw.eq("workspace_id", workspaceId);
        }
        if (conversationId != null) {
            qw.eq("conversation_id", conversationId);
        }
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            qw.and(w -> w.like("summary", kw).or().like("input", kw));
        }
        if (startDate != null && !startDate.isBlank()) {
            try {
                qw.ge("create_time", parseDateStart(startDate));
            } catch (Exception ignore) { /* skip invalid date */ }
        }
        if (endDate != null && !endDate.isBlank()) {
            try {
                qw.le("create_time", parseDateEnd(endDate));
            } catch (Exception ignore) { /* skip invalid date */ }
        }
        if (conversationId != null) {
            qw.orderByAsc("create_time");
        } else {
            qw.orderByDesc("create_time");
        }
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

    /**
     * Cascade-delete an execution and its parent conversation with all related data.
     * Deletes: steps → execution → sibling executions' steps → sibling executions →
     * conversation_details → conversation.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteExecution(Long executionId) {
        AgentExecution exec = agentExecutionDao.selectById(executionId);
        if (exec == null) return;

        Long conversationId = exec.getConversationId();

        if (conversationId != null) {
            // Find all executions in this conversation
            List<AgentExecution> siblings = agentExecutionDao.selectList(
                    new QueryWrapper<AgentExecution>().eq("conversation_id", conversationId));

            // Delete steps for all sibling executions
            for (AgentExecution sib : siblings) {
                agentExecutionStepDao.delete(
                        new QueryWrapper<AgentExecutionStep>().eq("execution_id", sib.getId()));
            }

            // Delete all sibling executions
            agentExecutionDao.delete(
                    new QueryWrapper<AgentExecution>().eq("conversation_id", conversationId));

            // Delete conversation details
            conversationDetailDao.delete(
                    new QueryWrapper<ConversationDetail>().eq("conversation_id", conversationId));

            // Delete the conversation itself
            conversationDao.deleteById(conversationId);

            log.info("[AgentHistoryAppService] Cascade-deleted execution {} + conversation {} ({} executions)",
                    executionId, conversationId, siblings.size());
        } else {
            // No conversation linked — just delete the execution + its steps
            agentExecutionStepDao.delete(
                    new QueryWrapper<AgentExecutionStep>().eq("execution_id", executionId));
            agentExecutionDao.deleteById(executionId);

            log.info("[AgentHistoryAppService] Deleted standalone execution {}", executionId);
        }
    }

    private static LocalDateTime parseDateStart(String s) {
        s = s.trim();
        if (s.length() <= 10) {
            return LocalDate.parse(s).atStartOfDay();
        }
        return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static LocalDateTime parseDateEnd(String s) {
        s = s.trim();
        if (s.length() <= 10) {
            return LocalDate.parse(s).atTime(LocalTime.MAX);
        }
        return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}