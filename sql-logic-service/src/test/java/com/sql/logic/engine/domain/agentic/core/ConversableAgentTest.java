package com.sql.logic.engine.domain.agentic.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ConversableAgentTest {

    private TestAgent agent;
    private SimpleTestMemory memory;

    /**
     * A minimal ConversableAgent subclass for testing the pipeline
     * without requiring a real LLM connection.
     */
    static class TestAgent extends ConversableAgent {
        private String cannedThinkingOutput = "THOUGHT: this is a test response";
        private ActionOutput cannedActOutput = ActionOutput.success("act done");
        private VerifyResult cannedVerifyResult = VerifyResult.PASSED;
        private int thinkingCallCount = 0;
        private int actCallCount = 0;
        private int verifyCallCount = 0;
        private boolean throwInThinking = false;
        private boolean throwInAct = false;
        private int failCountBeforeSuccess = 0;

        @Override
        protected String buildSystemPrompt(String obs, String mem, String res, Map<String, Object> ctx) {
            return "SYSTEM: You are a test agent.";
        }

        @Override
        protected String buildUserPrompt(String obs, String mem, String res, Map<String, Object> ctx) {
            return "USER: " + obs;
        }

        @Override
        protected String thinking(List<AgentMessage> messages) {
            thinkingCallCount++;
            if (throwInThinking) throw new RuntimeException("thinking failed");
            return cannedThinkingOutput;
        }

        @Override
        public CompletableFuture<ActionOutput> act(AgentMessage msg, Agent sender) {
            actCallCount++;
            if (throwInAct) throw new RuntimeException("act failed");
            return CompletableFuture.completedFuture(cannedActOutput);
        }

        @Override
        public CompletableFuture<VerifyResult> verify(AgentMessage msg, Agent sender) {
            verifyCallCount++;
            if (failCountBeforeSuccess > 0) {
                failCountBeforeSuccess--;
                return CompletableFuture.completedFuture(VerifyResult.fail("not yet"));
            }
            return CompletableFuture.completedFuture(cannedVerifyResult);
        }

        // Test control methods
        void setCannedThinkingOutput(String s) { this.cannedThinkingOutput = s; }
        void setCannedActOutput(ActionOutput ao) { this.cannedActOutput = ao; }
        void setCannedVerifyResult(VerifyResult vr) { this.cannedVerifyResult = vr; }
        void setThrowInThinking(boolean b) { this.throwInThinking = b; }
        void setThrowInAct(boolean b) { this.throwInAct = b; }
        void setFailCountBeforeSuccess(int n) { this.failCountBeforeSuccess = n; }
        int getThinkingCallCount() { return thinkingCallCount; }
        int getActCallCount() { return actCallCount; }
        int getVerifyCallCount() { return verifyCallCount; }
    }

    static class SimpleTestMemory implements AgentMemory {
        private final java.util.Deque<MemoryFragment> fragments = new java.util.ArrayDeque<>();
        private final java.util.List<TaskProgressEntry> progress = new java.util.ArrayList<>();

        @Override public String read(String query) {
            return fragments.isEmpty() ? "" : fragments.peekFirst().observation();
        }
        @Override public void write(MemoryFragment fragment) {
            fragments.addFirst(fragment);
        }
        @Override public void recordTaskProgress(TaskProgressEntry entry) {
            progress.add(entry);
        }
        @Override public String getTaskProgressSummary() {
            if (progress.isEmpty()) return null;
            return "Progress: " + progress.size() + " steps";
        }
        @Override public List<MemoryFragment> getRecentFragments(int count) {
            return fragments.stream().limit(count).toList();
        }
        int fragmentCount() { return fragments.size(); }
        int progressCount() { return progress.size(); }
    }

    @BeforeEach
    void setUp() {
        agent = new TestAgent();
        memory = new SimpleTestMemory();
        agent.bind(com.sql.logic.engine.domain.agentic.profile.ProfileConfig.builder()
                .name("TestAgent")
                .role("Tester")
                .goal("Testing")
                .build());
        agent.bind(memory);
        agent.build();
    }

    @Test
    void shouldExecuteFullPipelineSuccessfully() throws Exception {
        AgentMessage input = AgentMessage.user("test input");
        AgentMessage result = agent.generateReply(input, null, null, null)
                .get(10, TimeUnit.SECONDS);

        assertTrue(result.success());
        assertEquals("THOUGHT: this is a test response", result.content());
        assertNotNull(result.actionReport());
        assertTrue(result.actionReport().isExeSuccess());
        assertEquals(1, agent.getThinkingCallCount());
        assertEquals(1, agent.getActCallCount());
        assertEquals(1, agent.getVerifyCallCount());
    }

    @Test
    void shouldRetryOnVerifyFailure() throws Exception {
        agent.setFailCountBeforeSuccess(2); // Fail twice, succeed on third

        AgentMessage input = AgentMessage.user("test");
        AgentMessage result = agent.generateReply(input, null, null, null)
                .get(10, TimeUnit.SECONDS);

        assertTrue(result.success());
        assertEquals(3, agent.getThinkingCallCount(), "Should have called thinking 3 times (2 failures + 1 success)");
        assertEquals(3, agent.getVerifyCallCount());
    }

    @Test
    void shouldWriteMemoryOnSuccess() throws Exception {
        AgentMessage input = AgentMessage.user("test query");
        agent.generateReply(input, null, null, null).get(10, TimeUnit.SECONDS);

        assertTrue(memory.fragmentCount() > 0, "Should write at least one memory fragment on success");
    }

    @Test
    void shouldWriteMemoryOnFailure() throws Exception {
        agent.setCannedVerifyResult(VerifyResult.fail("bad"));
        agent.maxRetryCount = 2;

        AgentMessage input = AgentMessage.user("test");
        AgentMessage result = agent.generateReply(input, null, null, null)
                .get(10, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(memory.fragmentCount() > 0, "Should write failure memory fragments");
    }

    @Test
    void shouldNotExceedMaxRetryCount() throws Exception {
        agent.setCannedVerifyResult(VerifyResult.fail("always fail"));
        agent.maxRetryCount = 2;

        AgentMessage input = AgentMessage.user("test");
        AgentMessage result = agent.generateReply(input, null, null, null)
                .get(10, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertEquals(2, agent.getThinkingCallCount());
    }

    @Test
    void shouldHandleThinkingException() throws Exception {
        agent.setThrowInThinking(true);

        AgentMessage input = AgentMessage.user("test");
        AgentMessage result = agent.generateReply(input, null, null, null)
                .get(10, TimeUnit.SECONDS);

        assertFalse(result.success());
    }

    @Test
    void identityShouldDelegateToProfile() {
        assertEquals("TestAgent", agent.name());
        assertEquals("Tester", agent.role());
        assertEquals("Testing", agent.goal());
    }

    @Test
    void actShouldReturnCannedOutput() throws Exception {
        AgentMessage msg = AgentMessage.ai("hello world");
        ActionOutput result = agent.act(msg, null).get(10, TimeUnit.SECONDS);

        assertTrue(result.isExeSuccess());
        assertEquals("act done", result.content());
    }

    @Test
    void defaultReviewShouldApprove() {
        ReviewInfo result = agent.review("anything");
        assertTrue(result.approved());
    }

    @Test
    void asNodeActionShouldReturnNonNull() {
        assertNotNull(agent.asNodeAction());
    }
}
