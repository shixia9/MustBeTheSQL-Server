package com.sql.logic.engine.domain.agentic.core.bus;

import com.sql.logic.engine.domain.agentic.core.ActionOutput;
import com.sql.logic.engine.domain.agentic.core.Agent;
import com.sql.logic.engine.domain.agentic.core.AgentMessage;
import com.sql.logic.engine.domain.agentic.core.ConversableAgent;
import com.sql.logic.engine.domain.agentic.profile.ProfileConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only ConversableAgent that returns a deterministic reply and records
 * every {@code generateReply} invocation. Used by the REQ-02 dispatcher tests
 * to verify OFF/BYPASS/SWITCH modes without an LLM.
 */
public class StubConversableAgent extends ConversableAgent {

    private final String agentName;
    private final String replyContent;
    private final boolean replySuccess;
    private final List<AgentMessage> receivedGoals = new CopyOnWriteArrayList<>();
    private final AtomicInteger replyCount = new AtomicInteger(0);

    public StubConversableAgent(String name, String replyContent, boolean replySuccess) {
        this.agentName = name;
        this.replyContent = replyContent;
        this.replySuccess = replySuccess;
        this.profile = ProfileConfig.builder()
                .name(name).role("tester").goal("test").build();
    }

    @Override
    public String name() { return agentName; }

    @Override
    public CompletableFuture<AgentMessage> generateReply(
            AgentMessage msg, Agent sender, List<AgentMessage> relyMessages,
            List<AgentMessage> historicalDialogues) {
        receivedGoals.add(msg);
        replyCount.incrementAndGet();
        return CompletableFuture.completedFuture(
                AgentMessage.builder()
                        .content(replyContent)
                        .success(replySuccess)
                        .actionReport(ActionOutput.success(replyContent))
                        .build()
        );
    }

    @Override
    protected String buildSystemPrompt(String o, String m, String r, Map<String, Object> c) { return ""; }
    @Override
    protected String buildUserPrompt(String o, String m, String r, Map<String, Object> c) { return o; }

    public List<AgentMessage> receivedGoals() { return receivedGoals; }
    public int replyCount() { return replyCount.get(); }
}
