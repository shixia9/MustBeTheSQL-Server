package com.sql.logic.engine.domain.agentic.profile;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple variable substitution renderer for profile prompt templates.
 * <p>
 * Templates use {@code {variableName}} placeholders (same syntax as the
 * existing {@code .st} StringTemplate files). This renderer performs
 * straight string substitution without the StringTemplate engine,
 * suitable for Agent profile prompts that have simpler variable needs.
 * <p>
 * For full StringTemplate rendering (used by node prompts), the existing
 * {@code PromptManager} should be used instead.
 */
public class ProfileRenderer {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{(\\w+)}");

    /**
     * Substitute {@code {variableName}} placeholders in the template
     * with values from the provided map. Unknown variables are left as-is.
     */
    public String render(String template, Map<String, Object> variables) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (variables == null || variables.isEmpty()) {
            return template;
        }

        Matcher m = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String varName = m.group(1);
            Object value = variables.get(varName);
            String replacement = value != null ? String.valueOf(value) : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Convenience: render with a single variable.
     */
    public String render(String template, String varName, Object value) {
        return render(template, Map.of(varName, value));
    }
}
