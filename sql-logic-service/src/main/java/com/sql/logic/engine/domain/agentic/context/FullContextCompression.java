package com.sql.logic.engine.domain.agentic.context;

import com.sql.logic.engine.domain.agent.strategy.LLMStrategy;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 3 — LLM-generated structured summary replaces old messages.
 * <p>
 * When the context budget hits ERROR level, this layer calls the LLM to
 * summarize old conversation rounds into a single structured summary message.
 * The last N recent rounds are preserved as-is for continuity.
 */
public class FullContextCompression {

    private static final Logger log = LoggerFactory.getLogger(FullContextCompression.class);

    private static final String SUMMARY_PROMPT = """
            You are a context summarizer for a ReAct agent. Condense the conversation history \
            into a structured summary. Be precise — preserve exact names, paths, values, and \
            variable names.

            ## Conversation to summarize
            %s

            ## Output format (use exactly these headings)
            1. Original Task: <one-line description>
            2. Completed Steps: <one line per step, action + key result>
            3. Current State: <what the agent currently knows>
            4. Key Data: <important values, paths, variable names — must be exact>
            5. Errors Encountered: <failures and how they were resolved, or "None">
            6. Next Steps: <remaining work, or "None" if task is complete>""";

    /**
     * Apply Layer 3 compaction: summarize old rounds via LLM.
     * Returns a new list with the summary message prepended before recent rounds.
     */
    public List<AgentMessage> compact(List<AgentMessage> messages, LLMStrategy llmStrategy,
                                      ContextBudgetTracker tracker) {
        var cfg = tracker.getConfig();

        List<AgentMessage> systemMsgs = new ArrayList<>();
        List<AgentMessage> convMsgs = new ArrayList<>();
        for (AgentMessage msg : messages) {
            if (msg.messageType() == AgentMessage.MessageType.SYSTEM) {
                systemMsgs.add(msg);
            } else {
                convMsgs.add(msg);
            }
        }

        if (convMsgs.isEmpty()) return messages;

        List<List<Integer>> rounds = SessionMemoryCompact.detectRounds(convMsgs);
        int keepRounds = Math.min(cfg.minKeepRecentRounds(), rounds.size());
        if (keepRounds >= rounds.size()) return messages;

        // Split: old rounds to summarize, recent rounds to keep
        int split = rounds.size() - keepRounds;
        java.util.Set<Integer> oldIndices = new java.util.HashSet<>();
        for (int r = 0; r < split; r++) {
            oldIndices.addAll(rounds.get(r));
        }

        List<AgentMessage> oldMsgs = new ArrayList<>();
        List<AgentMessage> recentMsgs = new ArrayList<>();
        for (int i = 0; i < convMsgs.size(); i++) {
            if (oldIndices.contains(i)) {
                oldMsgs.add(convMsgs.get(i));
            } else {
                recentMsgs.add(convMsgs.get(i));
            }
        }

        // Build conversation text for summarization
        StringBuilder convText = new StringBuilder();
        for (AgentMessage msg : oldMsgs) {
            String role = msg.messageType().name();
            String content = truncate(msg.content(), 3000);
            convText.append("[").append(role).append("]: ").append(content).append("\n");
        }

        String prompt = String.format(SUMMARY_PROMPT, convText.toString());

        try {
            String summary = llmStrategy.chat(prompt);

            AgentMessage summaryMsg = AgentMessage.builder()
                    .content("[Context Summary of " + oldMsgs.size() + " earlier messages]\n\n" + summary)
                    .messageType(AgentMessage.MessageType.USER)
                    .build();

            log.info("Layer 3 (FullContextCompression): summarized {} messages into 1 summary, keeping {} recent",
                    oldMsgs.size(), recentMsgs.size());

            List<AgentMessage> result = new ArrayList<>(systemMsgs);
            result.add(summaryMsg);
            result.addAll(recentMsgs);
            return result;
        } catch (Exception e) {
            log.warn("Layer 3 compaction LLM call failed: {}", e.getMessage());
            throw new RuntimeException("FullContextCompression failed", e);
        }
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
