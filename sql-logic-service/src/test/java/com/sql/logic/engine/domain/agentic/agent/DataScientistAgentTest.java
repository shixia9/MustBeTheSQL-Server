package com.sql.logic.engine.domain.agentic.agent;

import com.sql.logic.engine.domain.agentic.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DataScientistAgentTest {

    private DataScientistAgent agent;
    private SimpleTestMemory memory;

    static class SimpleTestMemory implements AgentMemory {
        private final java.util.Deque<MemoryFragment> fragments = new java.util.ArrayDeque<>();
        private final java.util.List<TaskProgressEntry> progress = new java.util.ArrayList<>();

        @Override public String read(String q) { return ""; }
        @Override public void write(MemoryFragment f) { fragments.addFirst(f); }
        @Override public void recordTaskProgress(TaskProgressEntry e) { progress.add(e); }
        @Override public String getTaskProgressSummary() { return null; }
        @Override public List<MemoryFragment> getRecentFragments(int c) { return List.of(); }
        @Override public List<MemoryFragment> clear() { fragments.clear(); return List.of(); }
        @Override public int totalFragmentCount() { return fragments.size(); }
        @Override public void flushToLongTerm() { /* no-op */ }
    }

    @BeforeEach
    void setUp() {
        agent = new DataScientistAgent();
        memory = new SimpleTestMemory();
        agent.bind(memory);
        agent.build();
    }

    @Test
    void shouldHaveCorrectDefaultProfile() {
        assertEquals("DataScientist", agent.name());
        assertEquals("数据科学家", agent.role());
        assertTrue(agent.goal().contains("SQL"));
        assertTrue(agent.constraints().contains("仅生成 SELECT 查询语句"));
        assertTrue(agent.constraints().size() >= 4);
    }

    @Test
    void shouldBuildSystemPromptWithMemoryAndResource() {
        String prompt = agent.buildSystemPrompt(
                "查询用户数据",
                "user prefers short SQL",
                "-- DDL for users table --\nCREATE TABLE users (id INT, name VARCHAR);",
                Map.of()
        );

        assertNotNull(prompt);
        assertTrue(prompt.contains("DataScientist"));
        assertTrue(prompt.contains("数据科学家"));
        assertTrue(prompt.contains("user prefers short SQL"));
        assertTrue(prompt.contains("DDL for users table"));
    }

    @Test
    void shouldBuildSystemPromptWithoutMemoryAndResource() {
        String prompt = agent.buildSystemPrompt("test", "", "", Map.of());
        assertNotNull(prompt);
        assertTrue(prompt.contains("DataScientist"));
    }

    @Test
    void shouldBuildUserPrompt() {
        String prompt = agent.buildUserPrompt("查询用户", "", "", Map.of());
        assertEquals("查询用户", prompt);
    }

    @Test
    void correctnessCheckShouldPassValidSelect() {
        ActionOutput output = ActionOutput.success("SELECT * FROM users");
        VerifyResult result = agent.correctnessCheck(null, output);
        assertTrue(result.passed());
    }

    @Test
    void correctnessCheckShouldPassValidWithClause() {
        ActionOutput output = ActionOutput.success("WITH cte AS (SELECT 1) SELECT * FROM cte");
        VerifyResult result = agent.correctnessCheck(null, output);
        assertTrue(result.passed());
    }

    @Test
    void correctnessCheckShouldRejectInsert() {
        ActionOutput output = ActionOutput.success("INSERT INTO users VALUES (1)");
        VerifyResult result = agent.correctnessCheck(null, output);
        assertFalse(result.passed());
        assertTrue(result.reason().contains("只读"));
    }

    @Test
    void correctnessCheckShouldRejectUpdate() {
        ActionOutput output = ActionOutput.success("UPDATE users SET name='x'");
        VerifyResult result = agent.correctnessCheck(null, output);
        assertFalse(result.passed());
    }

    @Test
    void correctnessCheckShouldRejectDelete() {
        ActionOutput output = ActionOutput.success("DELETE FROM users");
        VerifyResult result = agent.correctnessCheck(null, output);
        assertFalse(result.passed());
    }

    @Test
    void correctnessCheckShouldRejectEmptySql() {
        ActionOutput output = ActionOutput.success("");
        VerifyResult result = agent.correctnessCheck(null, output);
        assertFalse(result.passed());
        assertTrue(result.reason().contains("空"));
    }

    @Test
    void correctnessCheckShouldRejectNullSql() {
        ActionOutput output = ActionOutput.success(null);
        VerifyResult result = agent.correctnessCheck(null, output);
        assertFalse(result.passed());
    }

    @Test
    void correctnessCheckShouldPassValidSelectWithJoin() {
        ActionOutput output = ActionOutput.success(
                "SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id"
        );
        VerifyResult result = agent.correctnessCheck(null, output);
        assertTrue(result.passed());
    }

    @Test
    void verifyShouldDelegateToCorrectnessCheck() throws Exception {
        AgentMessage msg = AgentMessage.builder()
                .actionReport(ActionOutput.success("SELECT 1"))
                .build();

        VerifyResult result = agent.verify(msg, null).get(5, TimeUnit.SECONDS);
        assertTrue(result.passed());
    }

    @Test
    void verifyShouldFailWhenNoActionReport() throws Exception {
        AgentMessage msg = AgentMessage.ai("test");
        VerifyResult result = agent.verify(msg, null).get(5, TimeUnit.SECONDS);
        assertFalse(result.passed());
    }

    @Test
    void actWithoutActionsShouldReturnFailure() throws Exception {
        ActionOutput result = agent.act(
                AgentMessage.user("test"), null
        ).get(5, TimeUnit.SECONDS);

        assertFalse(result.isExeSuccess());
        assertTrue(result.content().contains("No actions"));
    }

    @Test
    void actShouldSelectFixActionWhenPreviousFailedWithRetry() throws Exception {
        // Register a fix action
        AgentAction fixAction = new AgentAction() {
            @Override public String name() { return "sql_fix"; }
            @Override public String description() { return "fix SQL"; }
            @Override
            public CompletableFuture<ActionOutput> execute(AgentMessage ctx, Agent a) {
                return CompletableFuture.completedFuture(ActionOutput.success("fixed SQL"));
            }
        };
        agent.bind(List.of(fixAction));

        AgentMessage msg = AgentMessage.builder()
                .content("SELECT * FROM bad_table")
                .actionReport(ActionOutput.fail("table not found", true))
                .build();

        ActionOutput result = agent.act(msg, null).get(5, TimeUnit.SECONDS);
        assertTrue(result.isExeSuccess());
        assertEquals("fixed SQL", result.content());
    }

    @Test
    void defaultVerifyPassesForValidSql() throws Exception {
        AgentMessage msg = AgentMessage.builder()
                .content("SELECT 1")
                .actionReport(ActionOutput.success("SELECT 1"))
                .build();

        VerifyResult result = agent.verify(msg, null).get(5, TimeUnit.SECONDS);
        assertTrue(result.passed());
    }
}
