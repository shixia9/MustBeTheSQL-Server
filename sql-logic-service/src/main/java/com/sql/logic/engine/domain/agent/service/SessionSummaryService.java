package com.sql.logic.engine.domain.agent.service;

import com.sql.logic.engine.domain.agent.core.LlmClientManager;
import com.sql.logic.engine.domain.agent.prompt.PromptManager;
import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Generates a short, human-readable title for one completed Agent session.
 * <p>
 * Uses the user's resolved {@link LLMStrategy} (the same small/fast model configured
 * for the session) to summarise the conversation into 6~16 Chinese characters. The
 * call is non-streaming and best-effort: any failure degrades to a truncated copy of
 * the original user input, so history recording is never blocked by summarisation.
 * <p>
 * Invoked from the controller after a run finishes (or pauses at HITL).
 */
@Service
public class SessionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummaryService.class);

    /** Hard ceiling on the returned title length (Chinese chars / mixed glyphs). */
    private static final int MAX_TITLE_LEN = 24;
    /** Cap on the conversation excerpt fed to the model to keep the prompt cheap. */
    private static final int MAX_REPORT_EXCERPT = 1200;
    /** Cap on the user input fed to the model. */
    private static final int MAX_INPUT_EXCERPT = 600;

    private final LlmClientManager llmClientManager;
    private final PromptManager promptManager;

    public SessionSummaryService(LlmClientManager llmClientManager, PromptManager promptManager) {
        this.llmClientManager = llmClientManager;
        this.promptManager = promptManager;
    }

    /**
     * Summarise one session into a short title.
     *
     * @param userInput           the original natural-language question
     * @param conversationSummary a compact transcript — typically the final report plus
     *                             any generated SQL / analysis highlights; may be blank
     * @param llmConfigId         the LLM config to use (resolved per user, fallback to system default)
     * @param userId              the user id for strategy resolution
     * @return a concise title; never {@code null}. Falls back to a truncated user input
     *         when the model is unavailable or returns nothing usable.
     */
    public String summarise(String userInput, String conversationSummary, Long llmConfigId, Long userId) {
        String input = userInput == null ? "" : userInput;
        try {
            LLMStrategy strategy = llmClientManager.resolveStrategy(llmConfigId, userId);
            if (strategy == null) {
                return fallback(input);
            }

            String prompt = promptManager.render("session-summary", Map.of(
                    "user_input", truncate(input, MAX_INPUT_EXCERPT),
                    "conversation_summary", truncate(conversationSummary == null ? "" : conversationSummary, MAX_REPORT_EXCERPT)
            ));

            String raw = strategy.generateSql(prompt, null);
            String title = normalise(raw);
            if (title.isEmpty()) {
                return fallback(input);
            }
            log.debug("[SessionSummaryService] summarised title='{}' (inputLen={}, reportLen={})",
                    title, input.length(), conversationSummary == null ? 0 : conversationSummary.length());
            return title;
        } catch (Exception e) {
            log.warn("[SessionSummaryService] summarisation failed, using fallback: {}", e.getMessage());
            return fallback(input);
        }
    }

    /** Strip stray wrappers/quotes/blanks and clamp length. */
    private String normalise(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Drop markdown code fences if the model wrapped the title
        if (s.startsWith("```")) {
            s = s.replaceAll("(?s)^```[^\\n]*\\n", "").replaceAll("(?s)\\n```$", "").trim();
        }
        // Strip leading labels like "标题：" / "Title:"
        s = s.replaceAll("^(?i)(标题|title)[：:]\\s*", "");
        // Remove surrounding quotes
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("「") && s.endsWith("」"))
                || (s.startsWith("『") && s.endsWith("』")) || (s.startsWith("【") && s.endsWith("】"))) {
            if (s.length() >= 2) s = s.substring(1, s.length() - 1).trim();
        }
        // Collapse internal newlines into a single space; drop trailing punctuation noise
        s = s.replaceAll("\\s+", " ").replaceAll("[。.？?！!]+$", "").trim();
        if (s.length() > MAX_TITLE_LEN) s = s.substring(0, MAX_TITLE_LEN);
        return s;
    }

    private String fallback(String input) {
        String s = input.trim();
        // Take the first line and clamp; empty remains a single placeholder so the
        // history list always has something to show.
        int nl = s.indexOf('\n');
        if (nl > 0) s = s.substring(0, nl);
        if (s.length() > MAX_TITLE_LEN) s = s.substring(0, MAX_TITLE_LEN);
        return s.isEmpty() ? "(未命名会话)" : s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}