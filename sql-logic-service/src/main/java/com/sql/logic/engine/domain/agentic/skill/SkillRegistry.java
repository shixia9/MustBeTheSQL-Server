package com.sql.logic.engine.domain.agentic.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for managing and injecting Skills into Agents.
 * <p>
 * Skills can be loaded from YAML/JSON classpath resources or registered
 * programmatically. The registry provides lookup by name, category filtering,
 * and prompt injection middleware for ConversableAgent.
 */
public class SkillRegistry {
    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final Map<String, List<String>> categoryIndex = new ConcurrentHashMap<>();

    /**
     * Load skills from a classpath JSON resource.
     * Expected format: [{"name":"...", "description":"...", ...}, ...]
     */
    public void loadFromResource(String resourcePath) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                log.info("Skill resource not found: {}", resourcePath);
                return;
            }
            List<Map<String, Object>> raw = objectMapper.readValue(in,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> entry : raw) {
                Skill skill = deserializeSkill(entry);
                register(skill);
            }
            log.info("Loaded {} skills from {}", raw.size(), resourcePath);
        } catch (Exception e) {
            log.warn("Failed to load skills from {}: {}", resourcePath, e.getMessage());
        }
    }

    /**
     * Register a skill programmatically.
     */
    public void register(Skill skill) {
        skills.put(skill.getName(), skill);
        categoryIndex.computeIfAbsent(skill.getCategory(), k -> new ArrayList<>())
                .add(skill.getName());
    }

    /**
     * Get a skill by name.
     */
    public Skill get(String name) {
        return skills.get(name);
    }

    /**
     * List all registered skills.
     */
    public Collection<Skill> listAll() {
        return List.copyOf(skills.values());
    }

    /**
     * List skills in a category.
     */
    public List<Skill> listByCategory(String category) {
        List<String> names = categoryIndex.getOrDefault(category, List.of());
        return names.stream().map(skills::get).filter(Objects::nonNull).toList();
    }

    /**
     * Get all registered skill names.
     */
    public Set<String> getSkillNames() {
        return Set.copyOf(skills.keySet());
    }

    /**
     * Build a combined system prompt fragment from a set of skill names.
     * <p>
     * This is the primary middleware hook — callers pass the user's question
     * and matching skills are injected into the Agent's system prompt.
     */
    public String buildInjectionPrompt(Collection<String> skillNames,
                                        Map<String, Object> variables) {
        if (skillNames == null || skillNames.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("### Active Skills\n");
        for (String name : skillNames) {
            Skill skill = skills.get(name);
            if (skill != null) {
                sb.append(skill.renderPrompt(variables)).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Find skills relevant to a query by simple keyword matching.
     * Phase 4 default: keyword overlap. Phase 5+: semantic/embedding matching.
     */
    public List<Skill> findRelevant(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        String lower = query.toLowerCase();
        return skills.values().stream()
                .filter(s -> {
                    String text = (s.getName() + " " + s.getDescription()
                            + " " + s.getCategory()).toLowerCase();
                    return text.contains(lower) || lower.contains(s.getName().toLowerCase());
                })
                .limit(limit > 0 ? limit : 3)
                .toList();
    }

    /**
     * Register built-in default skills for common analysis scenarios.
     */
    public void registerBuiltinSkills() {
        register(new Skill(
                "sales-analysis", "销售数据分析: 分析销售额、订单量、客单价等核心指标",
                "analysis",
                """
                        When analyzing sales data:
                        1. Start with overall metrics (total revenue, order count, average order value)
                        2. Break down by dimensions (time, region, product category, channel)
                        3. Compare against previous period for trend analysis
                        4. Identify top/bottom performers
                        5. Use appropriate aggregations (SUM, AVG, COUNT DISTINCT)
                        """,
                List.of("sql_generation", "sql_execution"),
                List.of("sales_schema", "revenue_metrics"),
                Map.of("default_metric", "revenue")
        ));

        register(new Skill(
                "user-retention", "用户留存分析: 分析用户留存率、流失点、留存曲线",
                "analysis",
                """
                        When analyzing user retention:
                        1. Define the cohort by first action date
                        2. Calculate Day-N retention (N=1,3,7,14,30)
                        3. Identify drop-off points in the user journey
                        4. Segment by acquisition channel or user attributes
                        5. Use window functions for cohort analysis (LAG, LEAD, ROW_NUMBER)
                        """,
                List.of("sql_generation", "sql_execution", "python_analysis"),
                List.of("user_schema", "retention_metrics"),
                Map.of("cohort_periods", "1,3,7,14,30")
        ));

        register(new Skill(
                "anomaly-detection", "异常检测: 识别数据中的异常值、突变点和异常模式",
                "analysis",
                """
                        When detecting anomalies:
                        1. Calculate baseline statistics (mean, stddev, percentiles)
                        2. Use z-score or IQR methods for outlier detection
                        3. Compare current vs historical trends
                        4. Flag values exceeding 2 standard deviations
                        5. Provide context on why flagged values are anomalous
                        """,
                List.of("sql_generation", "sql_execution", "python_analysis"),
                List.of("statistical_functions"),
                Map.of("threshold_zscore", "2.0")
        ));

        log.info("Registered {} built-in skills", skills.size());
    }

    @SuppressWarnings("unchecked")
    private Skill deserializeSkill(Map<String, Object> entry) {
        String name = (String) entry.getOrDefault("name", "unnamed");
        String description = (String) entry.getOrDefault("description", "");
        String category = (String) entry.getOrDefault("category", "general");
        String promptTemplate = (String) entry.getOrDefault("promptTemplate", "");
        List<String> requiredTools = (List<String>) entry.getOrDefault("requiredTools", List.of());
        List<String> requiredKnowledge = (List<String>) entry.getOrDefault(
                "requiredKnowledge", List.of());
        Map<String, Object> config = (Map<String, Object>) entry.getOrDefault(
                "config", Map.of());
        return new Skill(name, description, category, promptTemplate, requiredTools,
                requiredKnowledge, config);
    }
}
