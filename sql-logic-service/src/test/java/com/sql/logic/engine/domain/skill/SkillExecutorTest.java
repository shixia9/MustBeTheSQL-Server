package com.sql.logic.engine.domain.skill;

import com.sql.logic.engine.infrastructure.po.Skill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test cases for {@link SkillExecutor}.
 * <p>
 * Covers: template variable substitution, missing variables replacement,
 * access control (owner vs public vs stranger), archived-skill denial,
 * Matcher.quoteReplacement safety with backslashes/dollar signs.
 */
class SkillExecutorTest {

    /**
     * In-memory stub of SkillCatalogService for unit tests
     * that do not require a real DAO.
     */
    static class StubSkillCatalogService extends SkillCatalogService {
        private Skill storedSkill;

        StubSkillCatalogService() {
            super(null);
        }

        void setSkill(Skill s) { this.storedSkill = s; }

        @Override
        public Optional<Skill> getById(Long id) {
            if (storedSkill != null && storedSkill.getId().equals(id)) {
                return Optional.of(storedSkill);
            }
            return Optional.empty();
        }
    }

    private StubSkillCatalogService catalog;
    private SkillExecutor executor;

    @BeforeEach
    void setUp() {
        catalog = new StubSkillCatalogService();
        executor = new SkillExecutor(catalog);
    }

    // ==================== Template rendering ====================

    @Test
    void shouldRenderTemplateWithSingleVariable() {
        Skill skill = stubSkill(1L, 100L, "private", 1, "Hello ${name}");
        catalog.setSkill(skill);

        String result = executor.execute(1L, 100L, Map.of("name", "World"));
        assertEquals("Hello World", result);
    }

    @Test
    void shouldRenderTemplateWithMultipleVariables() {
        Skill skill = stubSkill(1L, 100L, "private", 1,
                "SELECT ${columns} FROM ${table} WHERE ${condition}");
        catalog.setSkill(skill);

        String result = executor.execute(1L, 100L, Map.of(
                "columns", "name, age",
                "table", "users",
                "condition", "age > 18"
        ));
        assertEquals("SELECT name, age FROM users WHERE age > 18", result);
    }

    @Test
    void shouldReplaceMissingVariablesWithEmptyString() {
        Skill skill = stubSkill(1L, 100L, "private", 1,
                "前缀${missing}后缀");
        catalog.setSkill(skill);

        String result = executor.execute(1L, 100L, Map.of());
        assertEquals("前缀后缀", result);
    }

    @Test
    void shouldHandleNullVariablesMap() {
        Skill skill = stubSkill(1L, 100L, "private", 1,
                "Hello ${name}");
        catalog.setSkill(skill);

        String result = executor.execute(1L, 100L, null);
        // ${name} not provided → replaced with ""
        assertEquals("Hello ", result);
    }

    @Test
    void shouldHandleNullPromptTemplate() {
        Skill skill = stubSkill(1L, 100L, "private", 1, null);
        catalog.setSkill(skill);

        String result = executor.execute(1L, 100L, Map.of());
        assertEquals("", result);
    }

    @Test
    void shouldHandleEmptyPromptTemplate() {
        Skill skill = stubSkill(1L, 100L, "private", 1, "");
        catalog.setSkill(skill);

        String result = executor.execute(1L, 100L, Map.of());
        assertEquals("", result);
    }

    @Test
    void shouldRenderTemplateWithNoVariables() {
        Skill skill = stubSkill(1L, 100L, "private", 1,
                "这是一个没有变量的模板");
        catalog.setSkill(skill);

        String result = executor.execute(1L, 100L, Map.of("unused", "value"));
        assertEquals("这是一个没有变量的模板", result);
    }

    // ==================== Matcher.quoteReplacement safety ====================

    @Test
    void shouldHandleBackslashInVariableValue() {
        Skill skill = stubSkill(1L, 100L, "private", 1, "Path: ${path}");
        catalog.setSkill(skill);

        // Without Matcher.quoteReplacement, "\t" would be interpreted as a tab
        String result = executor.execute(1L, 100L, Map.of("path", "C:\\test\\dir"));
        assertEquals("Path: C:\\test\\dir", result);
    }

    @Test
    void shouldHandleDollarSignInVariableValue() {
        Skill skill = stubSkill(1L, 100L, "private", 1, "Price: ${price}");
        catalog.setSkill(skill);

        // Without Matcher.quoteReplacement, "$1" would be treated as a back-reference
        String result = executor.execute(1L, 100L, Map.of("price", "$100.00"));
        assertEquals("Price: $100.00", result);
    }

    // ==================== Access control ====================

    @Test
    void shouldAllowOwnerAccess() {
        Skill skill = stubSkill(1L, 100L, "private", 1, "OK");
        catalog.setSkill(skill);

        String result = executor.execute(1L, 100L, Map.of());
        assertEquals("OK", result);
    }

    @Test
    void shouldAllowPublicAccessForAnyUser() {
        Skill skill = stubSkill(1L, 99L, "public", 1, "OK");
        catalog.setSkill(skill);

        // User 100 is NOT the owner, but the skill is public
        String result = executor.execute(1L, 100L, Map.of());
        assertEquals("OK", result);
    }

    @Test
    void shouldDenyPrivateAccessForNonOwner() {
        Skill skill = stubSkill(1L, 99L, "private", 1, "OK");
        catalog.setSkill(skill);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute(1L, 100L, Map.of()));
        assertTrue(ex.getMessage().contains("not accessible"));
    }

    @Test
    void shouldDenyAccessForArchivedSkill() {
        Skill skill = stubSkill(1L, 100L, "private", 0, "OK");
        catalog.setSkill(skill);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute(1L, 100L, Map.of()));
        assertTrue(ex.getMessage().contains("not active"));
    }

    @Test
    void shouldDenyAccessForNullStatusSkill() {
        Skill skill = stubSkill(1L, 100L, "private", null, "OK");
        catalog.setSkill(skill);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute(1L, 100L, Map.of()));
        assertTrue(ex.getMessage().contains("not active"));
    }

    @Test
    void shouldDenyAccessWhenSkillNotFound() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute(999L, 100L, Map.of()));
        assertTrue(ex.getMessage().contains("not found"));
    }

    // ==================== Public skill with null userId ====================

    @Test
    void shouldAllowPublicSkillAccessWithNullUserId() {
        Skill skill = stubSkill(1L, 99L, "public", 1, "OK");
        catalog.setSkill(skill);

        String result = executor.execute(1L, null, Map.of());
        assertEquals("OK", result);
    }

    // ==================== Helpers ====================

    private static Skill stubSkill(Long id, Long userId, String visibility,
                                    Integer status, String promptTemplate) {
        Skill s = new Skill();
        s.setId(id);
        s.setUserId(userId);
        s.setVisibility(visibility);
        s.setStatus(status);
        s.setPromptTemplate(promptTemplate);
        s.setName("test-skill");
        return s;
    }
}
