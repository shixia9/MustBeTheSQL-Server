package com.sql.logic.engine.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sql.logic.engine.domain.agentic.skill.Skill;
import com.sql.logic.engine.domain.agentic.skill.SkillEmbeddingService;
import com.sql.logic.engine.domain.agentic.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for Skill CRUD operations.
 */
@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private static final Logger log = LoggerFactory.getLogger(SkillController.class);

    private final SkillRegistry skillRegistry;
    private final SkillEmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    public SkillController(SkillRegistry skillRegistry,
                           SkillEmbeddingService embeddingService,
                           ObjectMapper objectMapper) {
        this.skillRegistry = skillRegistry;
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listSkills() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Skill skill : skillRegistry.listAll()) {
            result.add(skillToMap(skill));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getSkill(@PathVariable String id) {
        Skill skill = skillRegistry.get(id);
        if (skill == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(skillToMap(skill));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createSkill(@RequestBody Map<String, Object> body) {
        Skill skill = deserializeSkill(body);
        skillRegistry.register(skill);
        // Generate and store embedding for semantic matching
        try {
            var embedResult = generateSimpleEmbedding(skill.getName() + " " + skill.getDescription());
            embeddingService.storeEmbedding(skill.getName(), embedResult);
        } catch (Exception e) {
            log.debug("Embedding generation skipped: {}", e.getMessage());
        }
        return ResponseEntity.ok(skillToMap(skill));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateSkill(@PathVariable String id,
                                                            @RequestBody Map<String, Object> body) {
        if (skillRegistry.get(id) == null) return ResponseEntity.notFound().build();
        Skill skill = deserializeSkill(body);
        skillRegistry.register(skill);
        return ResponseEntity.ok(skillToMap(skill));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(@PathVariable String id) {
        // Skills are currently immutable in the registry; mark as deleted not implemented yet
        return ResponseEntity.ok().build();
    }

    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importSkill(@RequestBody Map<String, Object> body) {
        Skill skill = deserializeSkill(body);
        skillRegistry.register(skill);
        return ResponseEntity.ok(skillToMap(skill));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<Map<String, Object>> exportSkill(@PathVariable String id) {
        Skill skill = skillRegistry.get(id);
        if (skill == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(skillToMap(skill));
    }

    @SuppressWarnings("unchecked")
    private Skill deserializeSkill(Map<String, Object> entry) {
        String name = (String) entry.getOrDefault("name", "unnamed");
        String description = (String) entry.getOrDefault("description", "");
        String category = (String) entry.getOrDefault("category", "general");
        String promptTemplate = (String) entry.getOrDefault("promptTemplate", "");
        String version = (String) entry.getOrDefault("version", "1.0.0");
        List<String> requiredTools = (List<String>) entry.getOrDefault("requiredTools", List.of());
        List<String> requiredKnowledge = (List<String>) entry.getOrDefault("requiredKnowledge", List.of());
        List<String> tags = (List<String>) entry.getOrDefault("tags", List.of());
        boolean isPublic = Boolean.TRUE.equals(entry.get("isPublic"));
        String authorId = (String) entry.getOrDefault("authorId", null);
        Map<String, Object> config = (Map<String, Object>) entry.getOrDefault("config", Map.of());
        return new Skill(name, description, category, promptTemplate, requiredTools,
                requiredKnowledge, config, version, tags, isPublic, authorId, null);
    }

    private Map<String, Object> skillToMap(Skill s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("description", s.getDescription());
        m.put("category", s.getCategory());
        m.put("version", s.getVersion());
        m.put("tags", s.getTags());
        m.put("requiredTools", s.getRequiredTools());
        m.put("requiredKnowledge", s.getRequiredKnowledge());
        m.put("promptTemplate", s.getPromptTemplate());
        m.put("config", s.getConfig());
        m.put("isPublic", s.isPublic());
        m.put("authorId", s.getAuthorId());
        return m;
    }

    /**
     * Generate a simple "embedding" from text using character n-gram hashing.
     * MVP: generates a small fixed-size vector for cosine similarity comparison.
     * Production: replace with pgvector or LLM embedding API call.
     */
    private double[] generateSimpleEmbedding(String text) {
        int dim = 64;
        double[] vec = new double[dim];
        if (text == null || text.isBlank()) return vec;
        String lower = text.toLowerCase();
        for (int i = 0; i < lower.length() - 2; i++) {
            int hash = lower.substring(i, Math.min(i + 3, lower.length())).hashCode();
            vec[Math.abs(hash) % dim] += 1.0;
        }
        // Normalize
        double norm = 0.0;
        for (double v : vec) norm += v * v;
        if (norm > 0) {
            norm = Math.sqrt(norm);
            for (int i = 0; i < dim; i++) vec[i] /= norm;
        }
        return vec;
    }
}
