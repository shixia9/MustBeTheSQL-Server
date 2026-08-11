package com.sql.logic.engine.domain.agent.tool;

import com.sql.logic.engine.domain.skill.SkillCatalogService;
import com.sql.logic.engine.infrastructure.po.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for {@link ToolDiscoveryService}.
 * <p>
 * Covers: aggregation of builtin + MCP + skill items, correct
 * kind/invocationMode mapping per source, empty-result handling.
 */
class ToolDiscoveryServiceTest {

    /**
     * Stub registry seeded with known tools.
     */
    static class StubToolRegistry extends ToolRegistry {
        private final List<ToolDefinition> tools;

        StubToolRegistry(List<ToolDefinition> tools) {
            this.tools = tools;
        }

        @Override
        public List<ToolDefinition> listTools(Long userId) {
            return tools;
        }
    }

    /**
     * Stub catalog returning a fixed skill list.
     */
    static class StubSkillCatalogService extends SkillCatalogService {
        private final List<Skill> skills;

        StubSkillCatalogService(List<Skill> skills) {
            super(null);
            this.skills = skills;
        }

        @Override
        public List<Skill> listByUser(Long userId) {
            return skills;
        }
    }

    private ToolDiscoveryService discoveryService;
    private StubToolRegistry toolRegistry;
    private StubSkillCatalogService skillCatalog;

    @BeforeEach
    void setUp() {
        // Seed registry: 2 builtins + 1 MCP tool for user 100
        toolRegistry = new StubToolRegistry(List.of(
                new ToolDefinition("sql", "SQL Executor", "Execute SQL",
                        ToolType.BUILTIN, null, null, null, ToolSource.BUILTIN),
                new ToolDefinition("schema", "Schema Viewer", "Browse schema",
                        ToolType.BUILTIN, null, null, null, ToolSource.BUILTIN),
                new ToolDefinition("github_search", "GitHub Search", "Search GitHub",
                        ToolType.MCP_STDIO, "{\"type\":\"object\"}", 10L, 100L, ToolSource.MCP)
        ));

        // Seed catalog: 1 private skill + 1 public skill
        Skill privateSkill = new Skill();
        privateSkill.setId(1L);
        privateSkill.setName("data-analyzer");
        privateSkill.setDescription("数据分析技能");
        privateSkill.setUserId(100L);
        privateSkill.setVisibility("private");
        privateSkill.setStatus(1);

        Skill publicSkill = new Skill();
        publicSkill.setId(2L);
        publicSkill.setName("report-generator");
        publicSkill.setDescription("报告生成技能");
        publicSkill.setUserId(99L);
        publicSkill.setVisibility("public");
        publicSkill.setStatus(1);

        skillCatalog = new StubSkillCatalogService(List.of(privateSkill, publicSkill));
        discoveryService = new ToolDiscoveryService(toolRegistry, skillCatalog);
    }

    // ==================== Aggregation ====================

    @Test
    void shouldAggregateAllThreeKinds() {
        List<ToolItem> items = discoveryService.discover(100L);
        // 2 builtins + 1 MCP + 2 skills = 5
        assertEquals(5, items.size());
    }

    @Test
    void shouldCountEachKindCorrectly() {
        List<ToolItem> items = discoveryService.discover(100L);

        long builtins = items.stream().filter(i -> "builtin".equals(i.kind())).count();
        long mcps = items.stream().filter(i -> "mcp".equals(i.kind())).count();
        long skills = items.stream().filter(i -> "skill".equals(i.kind())).count();

        assertEquals(2, builtins);
        assertEquals(1, mcps);
        assertEquals(2, skills);
    }

    // ==================== Kind mapping ====================

    @Test
    void shouldMapBuiltinToolCorrectly() {
        List<ToolItem> items = discoveryService.discover(100L);
        ToolItem sql = items.stream()
                .filter(i -> "sql".equals(i.name()) && "builtin".equals(i.kind()))
                .findFirst().orElseThrow();

        assertEquals("builtin", sql.kind());
        assertEquals("call_tool", sql.invocationMode());
        assertEquals("BUILTIN", sql.source());
        assertEquals("SQL Executor", sql.displayName());
    }

    @Test
    void shouldMapMcpToolCorrectly() {
        List<ToolItem> items = discoveryService.discover(100L);
        ToolItem gh = items.stream()
                .filter(i -> "github_search".equals(i.name()))
                .findFirst().orElseThrow();

        assertEquals("mcp", gh.kind());
        assertEquals("call_tool", gh.invocationMode());
        assertEquals("MCP", gh.source());
        assertNotNull(gh.parametersSchema());
    }

    @Test
    void shouldMapSkillCorrectly() {
        List<ToolItem> items = discoveryService.discover(100L);
        ToolItem skill = items.stream()
                .filter(i -> "data-analyzer".equals(i.name()) && "skill".equals(i.kind()))
                .findFirst().orElseThrow();

        assertEquals("skill", skill.kind());
        assertEquals("inject_prompt", skill.invocationMode());
        assertEquals("SKILL", skill.source());
        assertNull(skill.parametersSchema());
    }

    // ==================== Skill items use inject_prompt ====================

    @Test
    void allSkillItemsShouldUseInjectPromptMode() {
        List<ToolItem> items = discoveryService.discover(100L);
        List<ToolItem> skillItems = items.stream()
                .filter(i -> "skill".equals(i.kind()))
                .toList();

        assertEquals(2, skillItems.size());
        for (ToolItem item : skillItems) {
            assertEquals("inject_prompt", item.invocationMode());
        }
    }

    // ==================== Skill built via registry also maps as skill ====================

    @Test
    void shouldMapRegistryRegisteredSkillAsInjectPrompt() {
        // A skill-backed tool registered directly in the ToolRegistry (SKILL source)
        toolRegistry = new StubToolRegistry(List.of(
                new ToolDefinition("my-skill", "My Skill", "desc",
                        ToolType.BUILTIN, null, null, 100L, ToolSource.SKILL)
        ));
        skillCatalog = new StubSkillCatalogService(List.of());
        discoveryService = new ToolDiscoveryService(toolRegistry, skillCatalog);

        List<ToolItem> items = discoveryService.discover(100L);
        assertEquals(1, items.size());

        ToolItem item = items.get(0);
        assertEquals("skill", item.kind());
        assertEquals("inject_prompt", item.invocationMode());
    }

    // ==================== Empty result ====================

    @Test
    void shouldReturnEmptyListWhenNothingDiscovered() {
        toolRegistry = new StubToolRegistry(List.of());
        skillCatalog = new StubSkillCatalogService(List.of());
        discoveryService = new ToolDiscoveryService(toolRegistry, skillCatalog);

        List<ToolItem> items = discoveryService.discover(100L);
        assertTrue(items.isEmpty());
    }
}
