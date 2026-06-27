package com.sql.logic.engine.domain.agent.prompt;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages StringTemplate (.st) prompt template files loaded from classpath:/prompts/.
 * <p>
 * Follows the reference project pattern where each prompt is a .st file
 * with {variable} placeholders, loaded once at startup.
 */
@Component
public class PromptManager {

    private final Map<String, PromptTemplate> templates = new ConcurrentHashMap<>();
    private final Map<String, String> templateStrings = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath*:prompts/*.st");

        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || !filename.endsWith(".st")) {
                continue;
            }
            // Remove .st extension to get the template name
            String templateName = filename.substring(0, filename.length() - 3);
            // e.g. "evidence-query-rewrite.st" -> "evidence-query-rewrite"
            try {
                PromptTemplate template = new PromptTemplate(resource);
                templates.put(templateName, template);
                // Also store the raw template string for direct rendering
                String templateContent = resource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                templateStrings.put(templateName, templateContent);
                System.out.println("[PromptManager] Loaded prompt template: " + templateName);
            } catch (Exception e) {
                System.err.println("[PromptManager] Failed to load prompt template: " + templateName + " - " + e.getMessage());
            }
        }

        System.out.println("[PromptManager] Loaded " + templates.size() + " prompt templates");
    }

    /**
     * Render a prompt template by name with the given variables.
     *
     * @param templateName the template name (without .st extension)
     * @param variables    the variables to substitute
     * @return the rendered prompt string
     */
    public String render(String templateName, Map<String, Object> variables) {
        PromptTemplate template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Prompt template not found: " + templateName +
                    ". Available templates: " + templates.keySet());
        }
        return template.render(variables);
    }

    /**
     * Get the PromptTemplate object by name.
     *
     * @param templateName the template name (without .st extension)
     * @return the PromptTemplate
     */
    public PromptTemplate getTemplate(String templateName) {
        PromptTemplate template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Prompt template not found: " + templateName +
                    ". Available templates: " + templates.keySet());
        }
        return template;
    }

    /**
     * Get the raw template string by name.
     *
     * @param templateName the template name (without .st extension)
     * @return the raw template string
     */
    public String getTemplateString(String templateName) {
        String content = templateStrings.get(templateName);
        if (content == null) {
            throw new IllegalArgumentException("Prompt template not found: " + templateName +
                    ". Available templates: " + templateStrings.keySet());
        }
        return content;
    }
}