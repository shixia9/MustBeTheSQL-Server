package com.sql.logic.engine.domain.agentic.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentProfileTest {

    @Test
    void shouldCreateProfileWithAllFields() {
        AgentProfile profile = new AgentProfile(
                "DataScientist",
                "数据科学家",
                "生成正确的 SQL 查询",
                List.of("仅 SELECT", "禁止虚构"),
                "精通 SQL 的数据分析专家"
        );

        assertEquals("DataScientist", profile.name());
        assertEquals("数据科学家", profile.role());
        assertEquals("生成正确的 SQL 查询", profile.goal());
        assertEquals(2, profile.constraints().size());
        assertTrue(profile.constraints().contains("仅 SELECT"));
        assertEquals("精通 SQL 的数据分析专家", profile.description());
    }

    @Test
    void nullConstraintsShouldBeEmptyList() {
        AgentProfile profile = new AgentProfile(
                "Test", "Tester", "test", null, "desc"
        );
        assertNotNull(profile.constraints());
        assertTrue(profile.constraints().isEmpty());
    }

    @Test
    void constraintsShouldBeImmutable() {
        AgentProfile profile = new AgentProfile(
                "Test", "Tester", "test", List.of("c1"), "desc"
        );
        assertThrows(UnsupportedOperationException.class, () -> profile.constraints().add("c2"));
    }

    @Test
    void profilesWithSameFieldsShouldBeEqual() {
        AgentProfile p1 = new AgentProfile("a", "r", "g", List.of("c"), "d");
        AgentProfile p2 = new AgentProfile("a", "r", "g", List.of("c"), "d");
        assertEquals(p1, p2);
        assertEquals(p1.hashCode(), p2.hashCode());
    }
}
