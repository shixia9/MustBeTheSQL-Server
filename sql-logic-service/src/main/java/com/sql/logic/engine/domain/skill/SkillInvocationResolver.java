package com.sql.logic.engine.domain.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sql.logic.engine.infrastructure.po.Skill;

import java.util.Map;

/**
 * Resolves a {@code /skillName <task>} input submitted through the agentic
 * stream into the skill's rendered prompt, closing the {@code inject_prompt}
 * loop of the "/" command palette.
 * <p>
 * When the frontend palette offers a skill, it splices {@code /skillName } into
 * the input box; the user then types their task and submits. {@link #resolve}
 * detects that leading slash, looks up an active skill visible to the caller
 * via {@link SkillCatalogService#findByName}, renders its prompt template with
 * {@link SkillExecutor} (passing the typed task as the {@code ${task}} variable),
 * and returns the rendered prompt so the agent graph receives a concrete
 * instruction instead of a literal slash command.
 * <p>
 * Inputs that do not start with {@code /}, or whose first token is not a known
 * accessible skill, are returned unchanged so normal user messages and genuine
 * slash commands flow through untouched.
 */
@Component
public class SkillInvocationResolver {

    private static final Logger log = LoggerFactory.getLogger(SkillInvocationResolver.class);

    private final SkillCatalogService catalogService;
    private final SkillExecutor executor;

    public SkillInvocationResolver(SkillCatalogService catalogService, SkillExecutor executor) {
        this.catalogService = catalogService;
        this.executor = executor;
    }

    /**
     * Attempt to resolve a {@code /skillName <task>} input.
     *
     * @param userId    the calling user id (from the Sa-Token session)
     * @param userInput the raw user input submitted to the agentic stream
     * @return a {@link Result}; {@link Result#resolved()} is {@code true} when a
     *         skill was found and rendered, in which case {@link Result#resolvedInput()}
     *         carries the prompt to feed into the graph
     */
    public Result resolve(Long userId, String userInput) {
        if (userInput == null || !userInput.startsWith("/")) {
            return Result.notResolved();
        }
        // Parse "/skillName rest-of-task". The skill name is the first
        // whitespace-delimited token after the leading slash; the remainder is
        // the user's task text, passed to the template as ${task}.
        String body = userInput.substring(1);
        int spaceIdx = indexOfFirstWhitespace(body);
        String skillName = spaceIdx < 0 ? body : body.substring(0, spaceIdx);
        String task = spaceIdx < 0 ? "" : body.substring(spaceIdx + 1).trim();
        if (skillName.isEmpty()) {
            return Result.notResolved();
        }

        Skill skill = catalogService.findByName(userId, skillName);
        if (skill == null) {
            // Not a known skill — leave the input untouched (might be a literal
            // slash command or plain text the agent can handle).
            return Result.notResolved();
        }

        try {
            String rendered = executor.execute(skill.getId(), userId, Map.of("task", task));
            String resolved = rendered == null ? "" : rendered.strip();
            // If the template did not reference ${task}, the user's task would
            // otherwise be dropped — append it as a clearly labelled footer so
            // the agent always sees the concrete request.
            if (task != null && !task.isEmpty() && !resolved.contains(task)) {
                resolved = resolved + "\n\n# 用户任务\n" + task;
            }
            log.info("[SkillInvocationResolver] resolved skill '{}' (userId={})", skillName, userId);
            return Result.resolved(skillName, resolved);
        } catch (Exception e) {
            // Render/access failure must not break the chat — fall back to the
            // raw input so the user at least gets a normal agent response.
            log.warn("[SkillInvocationResolver] failed to render skill '{}': {}", skillName, e.getMessage());
            return Result.notResolved();
        }
    }

    private static int indexOfFirstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /** Outcome of a resolution attempt. */
    public record Result(boolean resolved, String skillName, String resolvedInput) {
        public static Result notResolved() {
            return new Result(false, null, null);
        }

        public static Result resolved(String skillName, String resolvedInput) {
            return new Result(true, skillName, resolvedInput);
        }
    }
}
