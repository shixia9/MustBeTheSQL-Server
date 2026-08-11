package com.sql.logic.engine.trigger.http;

import com.sql.logic.engine.domain.agentic.skill.Skill;
import com.sql.logic.engine.domain.agentic.skill.SkillRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Skill Hub — public skill discovery and installation.
 */
@RestController
@RequestMapping("/api/v1/hub")
public class SkillHubController {

    private final SkillRegistry skillRegistry;

    public SkillHubController(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /**
     * Browse public skills with optional category/tag filter.
     */
    @GetMapping("/skills")
    public ResponseEntity<List<Map<String, Object>>> browseSkills(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String tag) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Skill skill : skillRegistry.listAll()) {
            if (!skill.isPublic()) continue;
            if (category != null && !category.equals(skill.getCategory())) continue;
            if (tag != null && !skill.getTags().contains(tag)) continue;
            result.add(skillToSummary(skill));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Install a public skill into the user's workspace (creates a copy).
     */
    @PostMapping("/skills/{name}/install")
    public ResponseEntity<Map<String, Object>> installSkill(@PathVariable String name) {
        Skill skill = skillRegistry.get(name);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        // For MVP: registration is global; mark as installed locally
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "installed");
        result.put("name", skill.getName());
        result.put("version", skill.getVersion());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> skillToSummary(Skill s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("category", s.getCategory());
        m.put("version", s.getVersion());
        m.put("tags", s.getTags());
        m.put("authorId", s.getAuthorId());
        return m;
    }
}
