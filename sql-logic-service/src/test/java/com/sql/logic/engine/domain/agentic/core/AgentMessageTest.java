package com.sql.logic.engine.domain.agentic.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentMessageTest {

    @Test
    void builderShouldCreateMessageWithAllFields() {
        ActionOutput report = ActionOutput.success("SQL generated");
        ReviewInfo review = new ReviewInfo(true, "looks good");

        AgentMessage msg = AgentMessage.builder()
                .content("SELECT * FROM users")
                .currentGoal("查询用户数据")
                .senderName("DataScientist")
                .senderRole("数据科学家")
                .rounds(1)
                .success(false)
                .modelName("gpt-4")
                .actionReport(report)
                .reviewInfo(review)
                .putContext("dialect", "MySQL")
                .putResourceInfo("table", "users")
                .messageType(AgentMessage.MessageType.AI)
                .build();

        assertEquals("SELECT * FROM users", msg.content());
        assertEquals("查询用户数据", msg.currentGoal());
        assertEquals("DataScientist", msg.senderName());
        assertEquals("数据科学家", msg.senderRole());
        assertEquals(1, msg.rounds());
        assertFalse(msg.success());
        assertEquals("gpt-4", msg.modelName());
        assertSame(report, msg.actionReport());
        assertSame(review, msg.reviewInfo());
        assertEquals("MySQL", msg.context().get("dialect"));
        assertEquals("users", msg.resourceInfo().get("table"));
        assertEquals(AgentMessage.MessageType.AI, msg.messageType());
    }

    @Test
    void staticFactoriesShouldSetCorrectType() {
        AgentMessage sys = AgentMessage.system("system prompt");
        assertEquals(AgentMessage.MessageType.SYSTEM, sys.messageType());
        assertEquals("system prompt", sys.content());

        AgentMessage user = AgentMessage.user("user input");
        assertEquals(AgentMessage.MessageType.USER, user.messageType());

        AgentMessage ai = AgentMessage.ai("ai response");
        assertEquals(AgentMessage.MessageType.AI, ai.messageType());
    }

    @Test
    void withContentShouldReturnNewInstance() {
        AgentMessage original = AgentMessage.user("hello");
        AgentMessage modified = original.withContent("world");

        assertEquals("hello", original.content());
        assertEquals("world", modified.content());
        assertNotSame(original, modified);
    }

    @Test
    void withActionReportShouldReturnNewInstance() {
        AgentMessage original = AgentMessage.ai("test");
        ActionOutput report = ActionOutput.success("done");

        AgentMessage modified = original.withActionReport(report);

        assertNull(original.actionReport());
        assertSame(report, modified.actionReport());
        assertNotSame(original, modified);
    }

    @Test
    void withReviewInfoShouldReturnNewInstance() {
        AgentMessage original = AgentMessage.ai("test");
        ReviewInfo review = ReviewInfo.reject("bad");

        AgentMessage modified = original.withReviewInfo(review);

        assertNull(original.reviewInfo());
        assertSame(review, modified.reviewInfo());
    }

    @Test
    void withSuccessShouldToggleFlag() {
        AgentMessage original = AgentMessage.ai("test");
        assertFalse(original.success());

        AgentMessage modified = original.withSuccess(true);
        assertTrue(modified.success());
    }

    @Test
    void withContextShouldAddEntry() {
        AgentMessage original = AgentMessage.ai("test");
        AgentMessage modified = original.withContext("key", "value");

        assertTrue(original.context().isEmpty());
        assertEquals("value", modified.context().get("key"));
    }

    @Test
    void contextShouldBeImmutable() {
        AgentMessage msg = AgentMessage.builder()
                .putContext("key", "value")
                .build();

        assertThrows(UnsupportedOperationException.class, () -> msg.context().put("new", "value"));
    }

    @Test
    void builderFromExistingMessageShouldCopyAllFields() {
        AgentMessage original = AgentMessage.builder()
                .content("original")
                .senderName("sender")
                .rounds(5)
                .success(true)
                .putContext("a", "b")
                .build();

        AgentMessage copy = new AgentMessage.Builder(original).content("modified").build();

        assertEquals("modified", copy.content());
        assertEquals("sender", copy.senderName());
        assertEquals(5, copy.rounds());
        assertTrue(copy.success());
        assertEquals("b", copy.context().get("a"));
        assertNotSame(original, copy);
    }
}
