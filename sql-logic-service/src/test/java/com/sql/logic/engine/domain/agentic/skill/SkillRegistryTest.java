package com.sql.logic.engine.domain.agentic.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    private SkillRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SkillRegistry();
    }

    @Test
    void shouldRegisterAndRetrieveSkill() {
        Skill skill = new Skill("test-skill", "A test skill", "testing",
                "Do thing {param}", List.of("tool1"), List.of("knowledge1"),
                Map.of("key", "value"));
        registry.register(skill);

        Skill retrieved = registry.get("test-skill");
        assertNotNull(retrieved);
        assertEquals("test-skill", retrieved.getName());
        assertEquals("testing", retrieved.getCategory());
        assertEquals(List.of("tool1"), retrieved.getRequiredTools());
        assertEquals(List.of("knowledge1"), retrieved.getRequiredKnowledge());
    }

    @Test
    void shouldListAllSkills() {
        registry.register(new Skill("a", "desc", "cat", "", List.of(), List.of(), Map.of()));
        registry.register(new Skill("b", "desc", "cat", "", List.of(), List.of(), Map.of()));
        assertEquals(2, registry.listAll().size());
    }

    @Test
    void shouldListByCategory() {
        registry.register(new Skill("s1", "desc", "analysis", "", List.of(), List.of(), Map.of()));
        registry.register(new Skill("s2", "desc", "report", "", List.of(), List.of(), Map.of()));
        registry.register(new Skill("s3", "desc", "analysis", "", List.of(), List.of(), Map.of()));

        assertEquals(2, registry.listByCategory("analysis").size());
        assertEquals(1, registry.listByCategory("report").size());
        assertTrue(registry.listByCategory("nonexistent").isEmpty());
    }

    @Test
    void shouldGetSkillNames() {
        registry.register(new Skill("alpha", "", "cat", "", List.of(), List.of(), Map.of()));
        registry.register(new Skill("beta", "", "cat", "", List.of(), List.of(), Map.of()));
        assertTrue(registry.getSkillNames().contains("alpha"));
        assertTrue(registry.getSkillNames().contains("beta"));
    }

    @Test
    void shouldRenderPromptWithVariables() {
        Skill skill = new Skill("test", "desc", "cat",
                "Hello {name}, use {tool}", List.of(), List.of(), Map.of());
        String rendered = skill.renderPrompt(Map.of("name", "World", "tool", "SQL"));
        assertTrue(rendered.contains("Hello World"));
        assertTrue(rendered.contains("use SQL"));
    }

    @Test
    void shouldRenderPromptWithMissingVariables() {
        Skill skill = new Skill("test", "desc", "cat",
                "Hello {name}", List.of(), List.of(), Map.of());
        String rendered = skill.renderPrompt(Map.of());
        assertTrue(rendered.contains("{name}"));
    }

    @Test
    void shouldBuildSystemPromptFragment() {
        Skill skill = new Skill("analysis", "Analyze data", "analysis",
                "Do the analysis", List.of("sql_generation"),
                List.of("schema"), Map.of());
        String fragment = skill.toSystemPromptFragment();
        assertTrue(fragment.contains("analysis"));
        assertTrue(fragment.contains("Analyze data"));
        assertTrue(fragment.contains("sql_generation"));
        assertTrue(fragment.contains("schema"));
        assertTrue(fragment.contains("Do the analysis"));
    }

    @Test
    void shouldBuildInjectionPrompt() {
        registry.register(new Skill("s1", "First skill", "cat",
                "Guidance 1", List.of(), List.of(), Map.of()));
        registry.register(new Skill("s2", "Second skill", "cat",
                "Guidance 2", List.of(), List.of(), Map.of()));

        String prompt = registry.buildInjectionPrompt(List.of("s1", "s2"), Map.of());
        assertTrue(prompt.contains("Active Skills"));
        assertTrue(prompt.contains("Guidance 1"));
        assertTrue(prompt.contains("Guidance 2"));
    }

    @Test
    void shouldFindRelevantSkillsByKeyword() {
        registry.register(new Skill("sales-analysis", "Analyze sales data", "analysis",
                "", List.of(), List.of(), Map.of()));
        registry.register(new Skill("user-retention", "User retention analysis", "analysis",
                "", List.of(), List.of(), Map.of()));
        registry.register(new Skill("report-generator", "Generate reports", "report",
                "", List.of(), List.of(), Map.of()));

        List<Skill> relevant = registry.findRelevant("sales", 5);
        assertEquals(1, relevant.size());
        assertEquals("sales-analysis", relevant.get(0).getName());
    }

    @Test
    void shouldFindRelevantSkillsByCategory() {
        registry.register(new Skill("skill-a", "desc", "analysis", "", List.of(), List.of(), Map.of()));
        registry.register(new Skill("skill-b", "desc", "report", "", List.of(), List.of(), Map.of()));

        List<Skill> relevant = registry.findRelevant("report", 5);
        assertEquals(1, relevant.size());
        assertEquals("skill-b", relevant.get(0).getName());
    }

    @Test
    void shouldRegisterBuiltinSkills() {
        registry.registerBuiltinSkills();
        assertTrue(registry.listAll().size() >= 3);
        assertNotNull(registry.get("sales-analysis"));
        assertNotNull(registry.get("user-retention"));
        assertNotNull(registry.get("anomaly-detection"));
    }

    @Test
    void builtinSkillsShouldHaveRequiredFields() {
        registry.registerBuiltinSkills();
        for (Skill s : registry.listAll()) {
            assertNotNull(s.getName());
            assertFalse(s.getName().isBlank());
            assertNotNull(s.getDescription());
            assertFalse(s.getDescription().isBlank());
            assertNotNull(s.getCategory());
        }
    }

    @Test
    void emptyRegistryShouldReturnEmptyResults() {
        assertNull(registry.get("anything"));
        assertTrue(registry.listAll().isEmpty());
        assertTrue(registry.listByCategory("cat").isEmpty());
        assertTrue(registry.getSkillNames().isEmpty());
        assertTrue(registry.findRelevant("query", 5).isEmpty());
        assertEquals("", registry.buildInjectionPrompt(List.of(), Map.of()));
    }
}
