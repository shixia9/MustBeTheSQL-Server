package com.sql.logic.engine.domain.agentic.context;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Layer 2 — drop old conversation rounds, rely on task_progress as implicit summary.
 * <p>
 * No LLM call needed: the system prompt already contains the task progress
 * summary with a record of every completed step.
 * <p>
 * Strategy: group messages into rounds (AI → TOOL pairs), keep the most
 * recent N rounds plus any SYSTEM messages. Also respects {@code minKeepTokens}
 * to avoid over-compaction.
 */
public class SessionMemoryCompact {

    private static final Logger log = LoggerFactory.getLogger(SessionMemoryCompact.class);

    /**
     * Apply Layer 2 compaction: drop old rounds.
     *
     * @param messages     full message list
     * @param taskProgress task progress summary string (for logging)
     * @param tracker      budget tracker
     * @return compacted message list (fewer messages)
     */
    public List<AgentMessage> compact(List<AgentMessage> messages, String taskProgress,
                                      ContextBudgetTracker tracker) {
        var cfg = tracker.getConfig();

        // Separate system from conversation
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

        // Detect rounds: each AI+TOOL pair is a round
        List<List<Integer>> rounds = detectRounds(convMsgs);
        if (rounds.size() <= cfg.minKeepRecentRounds()) return messages;

        // Work backwards: keep at least minKeepRecentRounds + enough for minKeepTokens
        int keepFrom = rounds.size() - cfg.minKeepRecentRounds();
        int keptTokens = 0;
        for (int r = rounds.size() - 1; r >= 0; r--) {
            for (int idx : rounds.get(r)) {
                String content = convMsgs.get(idx).content();
                keptTokens += tracker.getTokenCounter().count(content != null ? content : "");
            }
            if (r < keepFrom && keptTokens >= cfg.minKeepTokens()) break;
            keepFrom = Math.min(keepFrom, r);
        }

        if (keepFrom <= 0) return messages;

        // Collect kept indices
        Set<Integer> keepIndices = new HashSet<>();
        for (int r = keepFrom; r < rounds.size(); r++) {
            keepIndices.addAll(rounds.get(r));
        }

        List<AgentMessage> kept = new ArrayList<>();
        for (int i = 0; i < convMsgs.size(); i++) {
            if (keepIndices.contains(i)) {
                kept.add(convMsgs.get(i));
            }
        }

        int dropped = convMsgs.size() - kept.size();
        if (dropped > 0) {
            log.info("Layer 2 (SessionMemoryCompact): dropped {} messages ({} rounds), keeping {} recent rounds",
                    dropped, keepFrom, rounds.size() - keepFrom);
        }

        List<AgentMessage> result = new ArrayList<>(systemMsgs);
        result.addAll(kept);
        return result;
    }

    /**
     * Detect round boundaries in conversation messages.
     * A round is defined as: non-TOOL messages followed by a TOOL (observation) message.
     * Each round is a list of message indices within convMsgs.
     */
    static List<List<Integer>> detectRounds(List<AgentMessage> convMsgs) {
        List<List<Integer>> rounds = new ArrayList<>();
        List<Integer> currentRound = new ArrayList<>();

        for (int i = 0; i < convMsgs.size(); i++) {
            AgentMessage msg = convMsgs.get(i);
            currentRound.add(i);

            if (msg.messageType() == AgentMessage.MessageType.TOOL) {
                rounds.add(currentRound);
                currentRound = new ArrayList<>();
            }
        }

        // Remaining messages that don't form a complete round
        if (!currentRound.isEmpty()) {
            rounds.add(currentRound);
        }

        return rounds;
    }
}
