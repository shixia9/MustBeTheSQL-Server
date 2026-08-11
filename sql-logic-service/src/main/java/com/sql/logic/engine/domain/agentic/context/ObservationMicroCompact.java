package com.sql.logic.engine.domain.agentic.context;

import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 1 — truncate old Observation (TOOL output) messages.
 * <p>
 * The lightest compaction: shortens tool outputs from old rounds while
 * preserving recent ones in full. No messages are removed — only truncated.
 */
public class ObservationMicroCompact {

    private static final Logger log = LoggerFactory.getLogger(ObservationMicroCompact.class);

    /**
     * Apply Layer 1 compaction: truncate old tool/observation messages.
     *
     * @param messages     full message list
     * @param currentRound current retry/round counter
     * @param tracker      budget tracker (provides config)
     * @return compacted message list (same length, some content truncated)
     */
    public List<AgentMessage> compact(List<AgentMessage> messages, int currentRound,
                                      ContextBudgetTracker tracker) {
        var cfg = tracker.getConfig();
        int cutoffRound = currentRound - cfg.maxObservationAgeRounds();
        int maxChars = cfg.truncatedObservationMaxChars();
        int truncated = 0;

        // Truncate old TOOL (observation) messages beyond the cutoff
        int toolMsgCount = 0;
        List<AgentMessage> result = new ArrayList<>(messages.size());
        for (AgentMessage msg : messages) {
            if (msg.messageType() == AgentMessage.MessageType.TOOL) {
                if (toolMsgCount < cutoffRound) {
                    String content = msg.content();
                    if (content != null && content.length() > maxChars + 30) {
                        msg = msg.withContent(content.substring(0, maxChars) + "... [truncated]");
                        truncated++;
                    }
                }
                toolMsgCount++;
            }
            result.add(msg);
        }

        if (truncated > 0) {
            log.info("Layer 1 (ObservationMicroCompact): truncated {} old observations", truncated);
        }
        return result;
    }
}
