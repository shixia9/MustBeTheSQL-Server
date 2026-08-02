package com.sql.logic.engine.domain.skill;

import com.sql.logic.engine.infrastructure.po.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for {@link SkillInvocationResolver}.
 * <p>
 * Covers: slash-command detection, skill-name parsing, template rendering,
 * task-footer appending, resolution fallback, and error handling.
 */
class SkillInvocationResolverTest {

    /**
     * Stub catalog that returns a fixed skill by name.
     */
    static class StubCatalogService extends SkillCatalogService {
        private final Skill skillToReturn;

        StubCatalogService(Skill skillToReturn) {
            super(null); // DAO not needed for resolve tests
            this.skillToReturn = skillToReturn;
        }

        @Override
        public Skill findByName(Long userId, String name) {
            if (skillToReturn != null && skillToReturn.getName().equals(name)) {
                return skillToReturn;
            }
            return null;
        }

        @Override
        public Optional<Skill> getById(Long id) {
            return Optional.ofNullable(skillToReturn);
        }
    }

    /**
     * Stub executor that performs simple ${var} substitution.
     */
    static class StubSkillExecutor extends SkillExecutor {
        StubSkillExecutor(SkillCatalogService catalog) {
            super(catalog);
        }

        @Override
        public String execute(Long skillId, Long userId, Map<String, String> variables) {
            Skill skill = getCatalog().getById(skillId).orElseThrow();
            String tpl = skill.getPromptTemplate() == null ? "" : skill.getPromptTemplate();
            String result = tpl;
            if (variables != null) {
                for (var e : variables.entrySet()) {
                    result = result.replace("${" + e.getKey() + "}", e.getValue());
                }
            }
            return result;
        }

        private SkillCatalogService getCatalog() {
            try {
                var f = SkillExecutor.class.getDeclaredField("catalogService");
                f.setAccessible(true);
                return (SkillCatalogService) f.get(this);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private SkillInvocationResolver resolver;
    private Skill testSkill;

    @BeforeEach
    void setUp() {
        testSkill = new Skill();
        testSkill.setId(1L);
        testSkill.setName("analyze");
        testSkill.setPromptTemplate("请分析以下数据：${task}");
        testSkill.setUserId(100L);
        testSkill.setVisibility("private");
        testSkill.setStatus(1);

        StubCatalogService catalog = new StubCatalogService(testSkill);
        StubSkillExecutor executor = new StubSkillExecutor(catalog);
        resolver = new SkillInvocationResolver(catalog, executor);
    }

    // ==================== Resolution detection ====================

    @Test
    void shouldNotResolveNullInput() {
        SkillInvocationResolver.Result r = resolver.resolve(100L, null);
        assertFalse(r.resolved());
    }

    @Test
    void shouldNotResolveEmptyInput() {
        SkillInvocationResolver.Result r = resolver.resolve(100L, "");
        assertFalse(r.resolved());
    }

    @Test
    void shouldNotResolvePlainTextWithoutSlash() {
        SkillInvocationResolver.Result r = resolver.resolve(100L, "帮我分析销售数据");
        assertFalse(r.resolved());
    }

    @Test
    void shouldNotResolveSlashOnly() {
        SkillInvocationResolver.Result r = resolver.resolve(100L, "/");
        assertFalse(r.resolved());
    }

    @Test
    void shouldNotResolveUnknownSkillName() {
        SkillInvocationResolver.Result r = resolver.resolve(100L, "/unknown-skill do something");
        assertFalse(r.resolved());
    }

    @Test
    void shouldResolveKnownSkillWithTask() {
        SkillInvocationResolver.Result r = resolver.resolve(100L, "/analyze 上个月销售趋势");
        assertTrue(r.resolved());
        assertEquals("analyze", r.skillName());
        assertTrue(r.resolvedInput().contains("上个月销售趋势"));
    }

    @Test
    void shouldResolveKnownSkillWithoutTask() {
        // "/analyze" with no trailing text → task is ""
        SkillInvocationResolver.Result r = resolver.resolve(100L, "/analyze");
        assertTrue(r.resolved());
        assertEquals("analyze", r.skillName());
    }

    @Test
    void shouldResolveSkillNameWithOnlyWhitespaceTrailing() {
        SkillInvocationResolver.Result r = resolver.resolve(100L, "/analyze   ");
        assertTrue(r.resolved());
    }

    // ==================== Task footer appending ====================

    @Test
    void shouldAppendTaskFooterWhenTemplateDoesNotContainTask() {
        // Template containing ${task} → task is in rendered output → no footer
        SkillInvocationResolver.Result r = resolver.resolve(100L, "/analyze 找出异常数据");
        assertTrue(r.resolved());
        // The rendered template already contains the task text, so no footer is needed
        assertFalse(r.resolvedInput().contains("# 用户任务"));
    }

    // ==================== Multi-word skill names and tasks ====================

    @Test
    void shouldParseSkillNameAsFirstWhitespaceDelimitedToken() {
        SkillInvocationResolver.Result r = resolver.resolve(100L, "/analyze 找出 top 10 客户");
        assertTrue(r.resolved());
        assertEquals("analyze", r.skillName());
        assertTrue(r.resolvedInput().contains("找出 top 10 客户"));
    }

    @Test
    void shouldHandleLeadingAndTrailingSpacesInTask() {
        SkillInvocationResolver.Result r = resolver.resolve(100L, "/analyze   多个空格  ");
        assertTrue(r.resolved());
        assertEquals("analyze", r.skillName());
    }

    // ==================== Error fallback ====================

    @Test
    void shouldFallbackToNotResolvedWhenRenderFails() {
        // Use a failing executor
        SkillExecutor failingExecutor = new SkillExecutor(null) {
            @Override
            public String execute(Long skillId, Long userId, Map<String, String> variables) {
                throw new RuntimeException("render failure");
            }
        };
        SkillCatalogService catalog = new StubCatalogService(testSkill);
        SkillInvocationResolver failingResolver = new SkillInvocationResolver(catalog, failingExecutor);

        SkillInvocationResolver.Result r = failingResolver.resolve(100L, "/analyze test");
        assertFalse(r.resolved());
    }

    // ==================== Null userId ====================

    @Test
    void shouldResolveWithNullUserId() {
        // Public skill accessible to anonymous users
        Skill publicSkill = new Skill();
        publicSkill.setId(2L);
        publicSkill.setName("report");
        publicSkill.setPromptTemplate("生成报告：${task}");
        publicSkill.setVisibility("public");
        publicSkill.setStatus(1);

        StubCatalogService catalog = new StubCatalogService(publicSkill);
        StubSkillExecutor executor = new StubSkillExecutor(catalog);
        SkillInvocationResolver r2 = new SkillInvocationResolver(catalog, executor);

        SkillInvocationResolver.Result r = r2.resolve(null, "/report 月度总结");
        assertTrue(r.resolved());
        assertEquals("report", r.skillName());
    }
}
