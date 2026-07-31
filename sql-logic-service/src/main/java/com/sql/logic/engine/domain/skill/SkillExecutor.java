package com.sql.logic.engine.domain.skill;

import com.sql.logic.engine.infrastructure.po.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a {@link Skill}'s prompt template into a concrete prompt.
 * <p>
 * Loads the skill via {@link SkillCatalogService}, verifies it is active and
 * accessible to the caller (owner or public), then substitutes {@code ${var}}
 * placeholders from the supplied variables map. Unknown variables are replaced
 * with the empty string (rather than left as literal {@code ${var}}) so the
 * resulting prompt never leaks placeholder syntax to the model.
 */
@Service
public class SkillExecutor {

    private static final Logger log = LoggerFactory.getLogger(SkillExecutor.class);
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{(\\w+)\\}");

    private final SkillCatalogService catalogService;

    public SkillExecutor(SkillCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Render the prompt template of skill {@code skillId} for user {@code userId}.
     *
     * @param skillId   target skill id
     * @param userId    caller user id (for access control)
     * @param variables placeholder name → value map; missing/unknown keys yield ""
     * @return the rendered prompt string
     * @throws IllegalStateException if the skill is missing, archived, or inaccessible
     */
    public String execute(Long skillId, Long userId, Map<String, String> variables) {
        Skill skill = catalogService.getById(skillId)
                .orElseThrow(() -> new IllegalStateException("Skill not found: " + skillId));

        if (skill.getStatus() == null || skill.getStatus() != 1) {
            throw new IllegalStateException("Skill is not active: " + skillId);
        }
        boolean owner = userId != null && userId.equals(skill.getUserId());
        boolean isPublic = "public".equalsIgnoreCase(skill.getVisibility());
        if (!owner && !isPublic) {
            throw new IllegalStateException("Skill " + skillId + " is not accessible by user " + userId);
        }

        Map<String, String> vars = variables == null ? Map.of() : variables;
        String template = skill.getPromptTemplate() == null ? "" : skill.getPromptTemplate();
        Matcher matcher = VAR_PATTERN.matcher(template);
        String rendered = matcher.replaceAll(mr -> {
            String key = mr.group(1);
            String val = vars.get(key);
            // Matcher.replaceAll treats backslashes/$ specially in replacement
            // strings, so quote the value to avoid IllegalArgumentException.
            return val == null ? "" : Matcher.quoteReplacement(val);
        });

        log.debug("[SkillExecutor] rendered skill={} for user={} ({} vars)", skillId, userId, vars.size());
        return rendered;
    }
}
