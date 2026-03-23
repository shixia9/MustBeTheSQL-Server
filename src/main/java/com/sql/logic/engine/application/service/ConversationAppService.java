package com.sql.logic.engine.application.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sql.logic.engine.infrastructure.dao.ConversationDao;
import com.sql.logic.engine.infrastructure.dao.ConversationDetailDao;
import com.sql.logic.engine.infrastructure.po.Conversation;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

@Service
public class ConversationAppService {

    private final ConversationDao conversationDao;
    private final ConversationDetailDao conversationDetailDao;

    public ConversationAppService(ConversationDao conversationDao, ConversationDetailDao conversationDetailDao) {
        this.conversationDao = conversationDao;
        this.conversationDetailDao = conversationDetailDao;
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

    public List<ConversationDetail> getConversationDetails(Long conversationId) {
        QueryWrapper<ConversationDetail> query = new QueryWrapper<>();
        query.eq("conversation_id", conversationId).orderByAsc("create_time");
        return conversationDetailDao.selectList(query);
    }
}
