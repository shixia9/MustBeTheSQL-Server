package com.sql.logic.engine.domain.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Python AST-level import blacklist validator — supplements {@code SecurityUtils}
 * with more precise pattern matching for Python import statements.
 *
 * <p>Unlike {@code SecurityUtils.validateCode} which does raw substring matching,
 * this validator strips comments and string literals first, then uses regex to
 * match actual import statements with word boundaries. This reduces false positives
 * (e.g. a string literal containing "import os" won't trigger a false alarm).
 *
 * <p>Blacklisted modules: {@code os}, {@code subprocess}, {@code socket},
 * {@code urllib}, {@code requests}, {@code pickle}. ({@code sys} is allowed — the
 * stdin/stdout execution protocol depends on it.) Plus dynamic execution:
 * {@code __import__}, {@code eval(}, {@code exec(}.
 */
public final class PythonAstValidator {

    private PythonAstValidator() {
    }

    /**
     * Modules that must not be imported in sandboxed Python code.
     * {@code sys} is deliberately NOT blacklisted — the sandbox stdin/stdout
     * protocol requires {@code json.load(sys.stdin)} and
     * {@code traceback.print_exc(file=sys.stderr)}, and {@code sys} exposes no
     * subprocess/file/network capabilities itself.
     */
    private static final String[] BLACKLISTED_MODULES = {
            "os", "subprocess", "socket", "urllib", "requests", "pickle",
            "ctypes", "multiprocessing", "shutil", "tempfile"
    };

    private static final Pattern IMPORT_DIRECT = Pattern.compile(
            "^\\s*import\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern IMPORT_FROM = Pattern.compile(
            "^\\s*from\\s+(\\w+)", Pattern.MULTILINE);
    private static final Pattern DUNDER_IMPORT = Pattern.compile(
            "__import__\\s*\\(");
    private static final Pattern EVAL_CALL = Pattern.compile(
            "\\beval\\s*\\(");
    private static final Pattern EXEC_CALL = Pattern.compile(
            "\\bexec\\s*\\(");
    private static final Pattern OPEN_WRITE = Pattern.compile(
            "open\\s*\\([^)]*['\"][wa]");

    /**
     * Validate Python code for blacklisted imports and dangerous calls.
     *
     * @param code the Python source code
     * @return a list of violation messages (empty if clean)
     */
    public static List<String> validate(String code) {
        List<String> violations = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return violations;
        }

        // Strip comments and string literals to reduce false positives.
        String stripped = stripCommentsAndStrings(code);

        // Check direct imports: "import os", "import os, sys"
        var importMatcher = IMPORT_DIRECT.matcher(stripped);
        while (importMatcher.find()) {
            String module = importMatcher.group(1);
            if (isBlacklisted(module)) {
                violations.add("Blacklisted import: import " + module);
            }
        }

        // Check from-imports: "from os import ..."
        var fromMatcher = IMPORT_FROM.matcher(stripped);
        while (fromMatcher.find()) {
            String module = fromMatcher.group(1);
            if (isBlacklisted(module)) {
                violations.add("Blacklisted import: from " + module + " import ...");
            }
        }

        // Check dynamic execution patterns.
        if (DUNDER_IMPORT.matcher(stripped).find()) {
            violations.add("Dynamic import via __import__() is not allowed");
        }
        if (EVAL_CALL.matcher(stripped).find()) {
            violations.add("eval() is not allowed in sandbox");
        }
        if (EXEC_CALL.matcher(stripped).find()) {
            violations.add("exec() is not allowed in sandbox");
        }
        // open(..., 'w'/'a') is checked against the RAW code, not the stripped
        // version, because the mode argument is itself a string literal that
        // stripCommentsAndStrings() would erase — defeating the check.
        if (OPEN_WRITE.matcher(code).find()) {
            violations.add("open() in write/append mode is not allowed in sandbox");
        }

        return violations;
    }

    /** True if the code contains any blacklisted import or dangerous call. */
    public static boolean isViolating(String code) {
        return !validate(code).isEmpty();
    }

    private static boolean isBlacklisted(String module) {
        for (String bl : BLACKLISTED_MODULES) {
            if (bl.equals(module)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strip Python line comments (# ...) and string literals (single/double/triple
     * quoted) to reduce false positives in pattern matching. This is a heuristic,
     * not a full lexer — it handles the common cases correctly.
     */
    private static String stripCommentsAndStrings(String code) {
        StringBuilder sb = new StringBuilder(code.length());
        int i = 0;
        int len = code.length();
        while (i < len) {
            char c = code.charAt(i);
            // Line comment
            if (c == '#') {
                // Skip to end of line
                while (i < len && code.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            // Triple-quoted strings
            if (i + 2 < len && c == '"' && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                i += 3;
                while (i + 2 < len && !(code.charAt(i) == '"'
                        && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"')) {
                    i++;
                }
                i += 3;
                sb.append("''");
                continue;
            }
            if (i + 2 < len && c == '\'' && code.charAt(i + 1) == '\'' && code.charAt(i + 2) == '\'') {
                i += 3;
                while (i + 2 < len && !(code.charAt(i) == '\''
                        && code.charAt(i + 1) == '\'' && code.charAt(i + 2) == '\'')) {
                    i++;
                }
                i += 3;
                sb.append("''");
                continue;
            }
            // Single/double quoted strings
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < len && code.charAt(i) != quote) {
                    if (code.charAt(i) == '\\' && i + 1 < len) {
                        i++; // skip escaped char
                    }
                    i++;
                }
                i++; // skip closing quote
                sb.append("''");
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
