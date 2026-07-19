package com.sql.logic.engine.domain.agentic.context;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 4 — emergency compaction when the LLM returns a context-too-long error.
 * <p>
 * This is the last resort: keeps only system prompt messages + the last 2
 * conversation rounds. The task progress summary in the system prompt ensures
 * the agent still knows its completed steps.
 */
public class ReactiveCompact {

    private static final Logger log = LoggerFactory.getLogger(ReactiveCompact.class);

    /**
     * Apply emergency compaction: keep system + last 2 rounds only.
     */
    public List<AgentMessage> compact(List<AgentMessage> messages,
                                      ContextBudgetTracker tracker) {
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
        int keepRounds = Math.min(2, rounds.size());
        if (keepRounds >= rounds.size()) return messages;

        java.util.Set<Integer> keepIndices = new java.util.HashSet<>();
        for (int r = rounds.size() - keepRounds; r < rounds.size(); r++) {
            keepIndices.addAll(rounds.get(r));
        }

        List<AgentMessage> kept = new ArrayList<>();
        for (int i = 0; i < convMsgs.size(); i++) {
            if (keepIndices.contains(i)) {
                kept.add(convMsgs.get(i));
            }
        }

        int dropped = convMsgs.size() - kept.size();
        log.warn("Layer 4 (ReactiveCompact): emergency drop of {} messages, keeping last {} rounds",
                dropped, keepRounds);

        List<AgentMessage> result = new ArrayList<>(systemMsgs);
        result.addAll(kept);
        return result;
    }
}
