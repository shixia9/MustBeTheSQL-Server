package com.sql.logic.engine.domain.conversation;

import com.sql.logic.engine.infrastructure.dao.ConversationDao;
import com.sql.logic.engine.infrastructure.dao.ConversationDetailDao;
import com.sql.logic.engine.infrastructure.po.Conversation;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * Conversation context window management.
 */
@Service
public class ConversationContextService {

    private static final Logger log = LoggerFactory.getLogger(ConversationContextService.class);

    /** Soft ceiling on the rendered history section, in estimated tokens. */
    private static final int MAX_HISTORY_TOKENS = 4000;
    /** Never inject more than this many recent turns, even if they'd fit the budget. */
    private static final int MAX_TURNS = 12;
    /** Coarse token estimate: ~2 chars per token for mixed CN/EN text. */
    private static final int CHARS_PER_TOKEN = 2;

    public enum OverflowStrategy { TRUNCATE, SUMMARIZE }

    private final ConversationDao conversationDao;
    private final ConversationDetailDao conversationDetailDao;

    public ConversationContextService(ConversationDao conversationDao,
                                      ConversationDetailDao conversationDetailDao) {
        this.conversationDao = conversationDao;
        this.conversationDetailDao = conversationDetailDao;
    }

    /**
     * Resolve or create a conversation for the user. When {@code conversationId} is
     * null/blank a fresh conversation is created titled with the (truncated) input.
     */
    public Conversation resolveConversation(Long conversationId, Long userId, String userInput, Long llmConfigId) {
        if (conversationId != null) {
            Conversation existing = conversationDao.selectById(conversationId);
            if (existing != null && (userId == null || userId.equals(existing.getUserId()))) {
                return existing;
            }
        }
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setTitle(truncateForTitle(userInput));
        conv.setLlmStrategyId(llmConfigId == null ? 0L : llmConfigId);
        conv.setCreateTime(new Date());
        conv.setUpdateTime(new Date());
        conversationDao.insert(conv);
        return conv;
    }

    /**
     * Render prior turns into a prompt-ready history section. Returns an empty string
     * when there is no conversation or no prior turns — callers should omit the slot.
     */
    public String loadHistorySection(Long conversationId) {
        if (conversationId == null) {
            return "";
        }
        try {
            List<ConversationDetail> details = conversationDetailDao.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ConversationDetail>()
                            .eq("conversation_id", conversationId)
                            .orderByDesc("create_time"));
            if (details == null || details.isEmpty()) {
                return "";
            }
            // Most recent first from the query; render oldest→newest within the window.
            return renderSlidingWindow(details);
        } catch (Exception e) {
            log.warn("[ConversationContextService] loadHistorySection failed: {}", e.getMessage());
            return "";
        }
    }

    /** Persist one completed turn so the next request can recall it. */
    public void appendTurn(Long conversationId, String userInput, String sqlOutput, String executeResult) {
        if (conversationId == null) {
            return;
        }
        try {
            ConversationDetail d = new ConversationDetail();
            d.setConversationId(conversationId);
            d.setUserInput(safe(userInput));
            d.setSqlOutput(safe(sqlOutput));
            d.setExecuteResult(safe(executeResult));
            d.setCreateTime(new Date());
            conversationDetailDao.insert(d);

            Conversation touch = new Conversation();
            touch.setId(conversationId);
            touch.setUpdateTime(new Date());
            conversationDao.updateById(touch);
        } catch (Exception e) {
            log.warn("[ConversationContextService] appendTurn failed: {}", e.getMessage());
        }
    }

    /** Update the conversation title after the first turn completes (AI-summarised). */
    public void updateTitle(Long conversationId, String title) {
        if (conversationId == null || title == null || title.isBlank()) return;
        try {
            Conversation touch = new Conversation();
            touch.setId(conversationId);
            touch.setTitle(truncateForTitle(title));
            touch.setUpdateTime(new Date());
            conversationDao.updateById(touch);
        } catch (Exception e) {
            log.warn("[ConversationContextService] updateTitle failed: {}", e.getMessage());
        }
    }

    /**
     * Sliding window: walk recent→old, accumulate turns while under the token budget
     * and turn cap, then emit oldest→newest. Each turn is compressed (no raw result
     * dump — just a short excerpt) to keep the slot cheap.
     */
    private String renderSlidingWindow(List<ConversationDetail> recentFirst) {
        int turns = 0;
        int used = 0;
        int idx = recentFirst.size(); // for numbering oldest→newest in output
        // We collect accepted turns (recent ones) then reverse for output order.
        java.util.List<ConversationDetail> accepted = new java.util.ArrayList<>();
        for (ConversationDetail d : recentFirst) {
            if (turns >= MAX_TURNS) break;
            String block = formatTurn(d, idx--);
            int cost = estimateTokens(block);
            if (used + cost > MAX_HISTORY_TOKENS) break;
            used += cost;
            turns++;
            accepted.add(d);
        }
        if (accepted.isEmpty()) return "";
        java.util.Collections.reverse(accepted);
        StringBuilder out = new StringBuilder();
        int n = 1;
        for (ConversationDetail d : accepted) {
            out.append(formatTurn(d, n++)).append("\n");
        }
        return out.toString().trim();
    }

    private String formatTurn(ConversationDetail d, int n) {
        StringBuilder sb = new StringBuilder();
        sb.append("[第").append(n).append("轮]\n");
        sb.append("用户: ").append(excerpt(d.getUserInput(), 800)).append("\n");
        if (d.getSqlOutput() != null && !d.getSqlOutput().isBlank()) {
            sb.append("SQL: ").append(excerpt(d.getSqlOutput(), 1000)).append("\n");
        }
        if (d.getExecuteResult() != null && !d.getExecuteResult().isBlank()) {
            sb.append("结果: ").append(excerpt(d.getExecuteResult(), 2000)).append("\n");
        }
        return sb.toString().trim();
    }

    private int estimateTokens(String s) {
        return s == null ? 0 : Math.max(1, s.length() / CHARS_PER_TOKEN);
    }

    private String excerpt(String s, int max) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    private String truncateForTitle(String s) {
        if (s == null || s.isBlank()) return "新会话";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 40 ? one : one.substring(0, 40);
    }

    private String safe(String s) { return s == null ? "" : s; }
}
