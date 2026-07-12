package com.sql.logic.engine.domain.conversation;

import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.infrastructure.dao.ConversationDao;
import com.sql.logic.engine.infrastructure.dao.ConversationDetailDao;
import com.sql.logic.engine.infrastructure.po.Conversation;
import com.sql.logic.engine.infrastructure.po.ConversationDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Conversation context window management.
 * <p>
 * Phase C extends the original TRUNCATE-only sliding window with a SUMMARIZE
 * strategy: when history overflows the token budget, the oldest turns are
 * LLM-summarised and cached on {@code conversation.summary_cache} so the key
 * context from early in the conversation is preserved.
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
    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;

    public ConversationContextService(ConversationDao conversationDao,
                                      ConversationDetailDao conversationDetailDao,
                                      LlmClientManager llmClientManager,
                                      PromptManager promptManager) {
        this.conversationDao = conversationDao;
        this.conversationDetailDao = conversationDetailDao;
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
    }

    /**
     * Resolve or create a conversation for the user. When {@code conversationId} is
     * null/blank, falls back to the most recently updated conversation for this user
     * (within a 2-hour window) before creating a new one — so follow-up questions
     * automatically rejoin the same conversation even if the frontend omits the id.
     */
    public Conversation resolveConversation(Long conversationId, Long userId, String userInput, Long llmConfigId) {
        if (conversationId != null) {
            Conversation existing = conversationDao.selectById(conversationId);
            if (existing != null && (userId == null || userId.equals(existing.getUserId()))) {
                return existing;
            }
        }
        if (userId != null) {
            Date twoHoursAgo = new Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000);
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Conversation>()
                    .eq("user_id", userId)
                    .ge("update_time", twoHoursAgo)
                    .orderByDesc("update_time")
                    .last("LIMIT 1");
            List<Conversation> recent = conversationDao.selectList(wrapper);
            if (recent != null && !recent.isEmpty()) {
                return recent.get(0);
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

    /** Load history with default TRUNCATE strategy (backward-compatible). */
    public String loadHistorySection(Long conversationId) {
        return loadHistorySection(conversationId, OverflowStrategy.TRUNCATE, null, null);
    }

    /**
     * Render prior turns into a prompt-ready history section.
     * When strategy is SUMMARIZE and a cached summary exists, it is prepended
     * before the recent turns. Otherwise behaves like TRUNCATE.
     */
    public String loadHistorySection(Long conversationId, OverflowStrategy strategy,
                                     Long llmConfigId, Long userId) {
        if (conversationId == null) return "";
        try {
            List<ConversationDetail> details = conversationDetailDao.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ConversationDetail>()
                            .eq("conversation_id", conversationId)
                            .orderByDesc("create_time"));
            if (details == null || details.isEmpty()) return "";

            // Check for cached summary when SUMMARIZE is active
            String cachedSummary = null;
            if (strategy == OverflowStrategy.SUMMARIZE) {
                Conversation conv = conversationDao.selectById(conversationId);
                if (conv != null && conv.getSummaryCache() != null && !conv.getSummaryCache().isBlank()) {
                    cachedSummary = conv.getSummaryCache();
                }
            }

            String recentTurns = renderSlidingWindow(details);
            if (cachedSummary != null) {
                return "【早期对话摘要】\n" + cachedSummary + "\n\n" + recentTurns;
            }
            return recentTurns;
        } catch (Exception e) {
            log.warn("[ConversationContextService] loadHistorySection failed: {}", e.getMessage());
            return "";
        }
    }

    /** Persist one completed turn so the next request can recall it. */
    public void appendTurn(Long conversationId, String userInput, String sqlOutput, String executeResult) {
        if (conversationId == null) return;
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
     * Generate and cache a summary of all conversation detail turns for this
     * conversation. Called after each turn completes when the SUMMARIZE strategy
     * is active. The summary replaces any previously cached summary.
     * <p>
     * Best-effort: failures are logged but never thrown — history recording must
     * never be blocked by summarisation.
     */
    public void generateAndCacheSummary(Long conversationId, Long llmConfigId, Long userId) {
        if (conversationId == null) return;
        try {
            List<ConversationDetail> details = conversationDetailDao.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ConversationDetail>()
                            .eq("conversation_id", conversationId)
                            .orderByAsc("create_time"));
            if (details == null || details.size() < 2) return; // not enough to summarise

            // Build a compact transcript of all turns
            StringBuilder turns = new StringBuilder();
            int n = 1;
            for (ConversationDetail d : details) {
                turns.append("[第").append(n++).append("轮]\n");
                if (d.getUserInput() != null && !d.getUserInput().isBlank()) {
                    turns.append("用户: ").append(excerpt(d.getUserInput(), 300)).append("\n");
                }
                if (d.getSqlOutput() != null && !d.getSqlOutput().isBlank()) {
                    turns.append("SQL: ").append(excerpt(d.getSqlOutput(), 400)).append("\n");
                }
                if (d.getExecuteResult() != null && !d.getExecuteResult().isBlank()) {
                    turns.append("结果: ").append(excerpt(d.getExecuteResult(), 500)).append("\n");
                }
                turns.append("\n");
            }

            LLMStrategy strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
            if (strategy == null) return;

            String prompt = promptManager.render("conversation-summary", Map.of(
                    "turns", turns.toString()
            ));

            String raw = strategy.generateSql(prompt, null);
            String summary = normaliseSummary(raw);
            if (summary.isEmpty()) return;

            Conversation touch = new Conversation();
            touch.setId(conversationId);
            touch.setSummaryCache(summary);
            touch.setUpdateTime(new Date());
            conversationDao.updateById(touch);

            log.info("[ConversationContextService] Cached summary for conversation {} ({} chars)", conversationId, summary.length());
        } catch (Exception e) {
            log.warn("[ConversationContextService] generateAndCacheSummary failed: {}", e.getMessage());
        }
    }

    // ---- private helpers ----

    /**
     * Sliding window: walk recent→old, accumulate turns while under the token budget
     * and turn cap, then emit oldest→newest.
     */
    private String renderSlidingWindow(List<ConversationDetail> recentFirst) {
        int turns = 0;
        int used = 0;
        int idx = recentFirst.size();
        List<ConversationDetail> accepted = new ArrayList<>();
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
        Collections.reverse(accepted);
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

    private String normaliseSummary(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("^```[^\\n]*\\n", "").replaceAll("\\n```$", "").trim();
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
