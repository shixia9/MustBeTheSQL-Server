package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sql.logic.engine.common.dto.ConversationSummaryDTO;
import com.sql.logic.engine.infrastructure.dao.AgentExecutionDao;
import com.sql.logic.engine.infrastructure.dao.AgentExecutionStepDao;
import com.sql.logic.engine.infrastructure.dao.ConversationDao;
import com.sql.logic.engine.infrastructure.dao.ConversationDetailDao;
import com.sql.logic.engine.infrastructure.po.AgentExecution;
import com.sql.logic.engine.infrastructure.po.AgentExecutionStep;
import com.sql.logic.engine.infrastructure.po.Conversation;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ConversationAppService {

    private static final Logger log = LoggerFactory.getLogger(ConversationAppService.class);

    private final ConversationDao conversationDao;
    private final ConversationDetailDao conversationDetailDao;
    private final AgentExecutionDao agentExecutionDao;
    private final AgentExecutionStepDao agentExecutionStepDao;

    public ConversationAppService(ConversationDao conversationDao,
                                  ConversationDetailDao conversationDetailDao,
                                  AgentExecutionDao agentExecutionDao,
                                  AgentExecutionStepDao agentExecutionStepDao) {
        this.conversationDao = conversationDao;
        this.conversationDetailDao = conversationDetailDao;
        this.agentExecutionDao = agentExecutionDao;
        this.agentExecutionStepDao = agentExecutionStepDao;
    }

    @Transactional(rollbackFor = Exception.class)
    public Conversation createConversation(Long userId, String title, Long llmStrategyId) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(title);
        conversation.setLlmStrategyId(llmStrategyId);
        conversation.setCreateTime(new Date());
        conversation.setUpdateTime(new Date());
        conversationDao.insert(conversation);
        return conversation;
    }

    @Transactional(rollbackFor = Exception.class)
    public void addDetail(Long conversationId, String userInput, String sqlOutput, String executeResult) {
        ConversationDetail detail = new ConversationDetail();
        detail.setConversationId(conversationId);
        detail.setUserInput(userInput);
        detail.setSqlOutput(sqlOutput);
        detail.setExecuteResult(executeResult);
        detail.setCreateTime(new Date());
        conversationDetailDao.insert(detail);

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setUpdateTime(new Date());
        conversationDao.updateById(conversation);
    }

    public List<Conversation> listConversations(Long userId) {
        QueryWrapper<Conversation> query = new QueryWrapper<>();
        query.eq("user_id", userId).orderByDesc("update_time");
        return conversationDao.selectList(query);
    }

    /** Paginated conversation list with optional title search and date range filter. */
    public Page<Conversation> listConversations(Long userId, int page, int size, String keyword,
                                                 String startDate, String endDate) {
        Page<Conversation> p = new Page<>(page, size);
        QueryWrapper<Conversation> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        if (keyword != null && !keyword.isBlank()) {
            qw.like("title", keyword.trim());
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
        qw.orderByDesc("update_time");
        return conversationDao.selectPage(p, qw);
    }

    /** Parse a date string (yyyy-MM-dd or yyyy-MM-dd HH:mm:ss) as start-of-day. */
    private static LocalDateTime parseDateStart(String s) {
        s = s.trim();
        if (s.length() <= 10) {
            return LocalDate.parse(s).atStartOfDay();
        }
        return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** Parse a date string (yyyy-MM-dd or yyyy-MM-dd HH:mm:ss) as end-of-day. */
    private static LocalDateTime parseDateEnd(String s) {
        s = s.trim();
        if (s.length() <= 10) {
            return LocalDate.parse(s).atTime(LocalTime.MAX);
        }
        return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    public List<ConversationDetail> getConversationDetails(Long conversationId) {
        QueryWrapper<ConversationDetail> query = new QueryWrapper<>();
        query.eq("conversation_id", conversationId).orderByAsc("create_time");
        return conversationDetailDao.selectList(query);
    }

    /**
     * Paginated conversation list enriched with turn-count and last-message preview.
     */
    public Page<ConversationSummaryDTO> listConversationSummaries(Long userId, int page, int size,
                                                                   String keyword, String startDate, String endDate) {
        Page<Conversation> p = listConversations(userId, page, size, keyword, startDate, endDate);
        List<Conversation> conversations = p.getRecords();

        Page<ConversationSummaryDTO> result = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        if (conversations.isEmpty()) {
            result.setRecords(List.of());
            return result;
        }

        List<Long> ids = conversations.stream().map(Conversation::getId).collect(Collectors.toList());

        // Batch-fetch turn counts
        Map<Long, Integer> countMap = new HashMap<>();
        List<Map<String, Object>> counts = conversationDetailDao.countByConversationIds(ids);
        for (Map<String, Object> row : counts) {
            Long cid = ((Number) row.get("conversation_id")).longValue();
            int cnt = ((Number) row.get("cnt")).intValue();
            countMap.put(cid, cnt);
        }

        // Batch-fetch last messages
        Map<Long, String> lastMsgMap = new HashMap<>();
        List<Map<String, Object>> lastMsgs = conversationDetailDao.selectLastMessages(ids);
        for (Map<String, Object> row : lastMsgs) {
            Long cid = ((Number) row.get("conversation_id")).longValue();
            String msg = (String) row.get("user_input");
            lastMsgMap.put(cid, msg != null ? msg : "");
        }

        List<ConversationSummaryDTO> summaries = conversations.stream().map(c -> {
            ConversationSummaryDTO dto = new ConversationSummaryDTO();
            dto.setId(c.getId());
            dto.setUserId(c.getUserId());
            dto.setTitle(c.getTitle());
            dto.setTurnCount(countMap.getOrDefault(c.getId(), 0));
            String lastMsg = lastMsgMap.getOrDefault(c.getId(), "");
            if (lastMsg.length() > 100) {
                lastMsg = lastMsg.substring(0, 100) + "...";
            }
            dto.setLastMessage(lastMsg);
            dto.setLastActiveTime(c.getUpdateTime());
            dto.setCreateTime(c.getCreateTime());
            return dto;
        }).collect(Collectors.toList());

        result.setRecords(summaries);
        return result;
    }

    /**
     * Cascade-delete a conversation and all related data:
     * agent_execution_step → agent_execution → conversation_detail → conversation.
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long conversationId) {
        // 1. Find all executions linked to this conversation
        List<AgentExecution> executions = agentExecutionDao.selectList(
                new QueryWrapper<AgentExecution>().eq("conversation_id", conversationId));

        // 2. Delete steps for each execution
        for (AgentExecution exec : executions) {
            agentExecutionStepDao.delete(
                    new QueryWrapper<AgentExecutionStep>().eq("execution_id", exec.getId()));
        }

        // 3. Delete executions
        agentExecutionDao.delete(
                new QueryWrapper<AgentExecution>().eq("conversation_id", conversationId));

        // 4. Delete conversation details
        conversationDetailDao.delete(
                new QueryWrapper<ConversationDetail>().eq("conversation_id", conversationId));

        // 5. Delete the conversation itself
        conversationDao.deleteById(conversationId);

        log.info("[ConversationAppService] Cascade-deleted conversation {} with {} executions",
                conversationId, executions.size());
    }
}
