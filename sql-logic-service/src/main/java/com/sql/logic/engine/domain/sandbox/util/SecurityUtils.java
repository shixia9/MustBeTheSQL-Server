package com.sql.logic.engine.domain.sandbox.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Static code security validator.
 *
 * <p>Performs pattern-based blacklist validation before code is executed. For the
 * LocalSandboxRuntime this is a <b>hard gate</b> (code is rejected if any warning is
 * produced). For DockerRuntime it is advisory (container isolation is the primary
 * defense), though the spec also requires Python AST import blacklisting across all
 * runtimes.
 *
 * <p>Pattern sets:
 * <ul>
 *   <li><b>Bash</b> (11 patterns): {@code rm -rf /}, {@code mkfs.}, {@code dd if=},
 *       fork bomb, {@code > /dev/sda}, {@code chmod -R 777 /},
 *       {@code curl|bash}, {@code wget|bash}, {@code curl|sh}, {@code wget|sh}.</li>
 *   <li><b>Python</b> (17 patterns): {@code import os}, {@code import subprocess},
 *       {@code __import__}, {@code eval(}, {@code exec(}, {@code open(}, {@code socket},
 *       {@code urllib}, {@code requests}, {@code rmdir}, {@code remove}, {@code unlink},
 *       {@code delete}, etc. Plus {@code pickle} advisory for Python.</li>
 * </ul>
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    // ---- Bash dangerous patterns ----
    // Each entry: {pattern, description}
    private static final String[][] BASH_PATTERNS = {
            {"rm -rf /", "Recursive deletion of root directory"},
            {"rm -rf /*", "Recursive deletion of all root files"},
            {"mkfs.", "Disk formatting"},
            {"dd if=", "Raw disk write"},
            {":(){ :|:& };:", "Fork bomb"},
            {"> /dev/sda", "Disk device overwrite"},
            {"chmod -R 777 /", "Global permission modification"},
            {"curl | bash", "Remote script execution"},
            {"wget | bash", "Remote script execution"},
            {"curl | sh", "Remote script execution"},
            {"wget | sh", "Remote script execution"},
    };

    // ---- Python / general dangerous patterns ----
    private static final String[] PYTHON_PATTERNS = {
            "import os",
            "import subprocess",
            "import sys",
            "__import__",
            "eval(",
            "exec(",
            "open(",
            "file(",
            "input(",
            "raw_input(",
            "socket",
            "urllib",
            "requests",
            "rmdir",
            "remove",
            "unlink",
            "delete",
    };

    /**
     * Validate code against the blacklist for the given language.
     *
     * @param code     the source code to validate
     * @param language language key (bash/shell triggers bash patterns; others trigger Python patterns)
     * @return a list of warning messages (empty if the code is clean)
     */
    public static List<String> validateCode(String code, String language) {
        List<String> warnings = new ArrayList<>();
        if (code == null || code.isBlank()) {
            return warnings;
        }

        String lang = language == null ? "" : language.toLowerCase();
        String codeLower = code.toLowerCase();

        if (lang.equals("bash") || lang.equals("shell") || lang.equals("sh")) {
            for (String[] entry : BASH_PATTERNS) {
                // Patterns are matched case-insensitively (code is lowercased), so
                // lowercase the pattern too — otherwise uppercase tokens like the
                // "-R" in "chmod -R 777 /" would never match.
                if (codeLower.contains(entry[0].toLowerCase())) {
                    warnings.add("Dangerous operation detected: " + entry[1] + " (" + entry[0] + ")");
                }
            }
            // Supplement the substring blacklist with regex-based shell-injection
            // detection. The substring patterns only catch the literal form
            // (e.g. "curl | bash"); the regex catches realistic variants where a
            // URL/args sit between the command and the pipe (e.g.
            // "curl http://evil | bash"), plus command-chaining and substitution.
            warnings.addAll(validateShellCommand(code));
        } else {
            for (String pattern : PYTHON_PATTERNS) {
                if (codeLower.contains(pattern)) {
                    warnings.add("Dangerous operation detected: " + pattern);
                }
            }
            if (lang.equals("python") && codeLower.contains("pickle")) {
                warnings.add("Dangerous operation detected: pickle module usage (security risk)");
            }
        }

        return warnings;
    }

    /** Convenience: true if the code contains any blacklisted pattern. */
    public static boolean isDangerous(String code, String language) {
        return !validateCode(code, language).isEmpty();
    }

    /**
     * Basic shell-injection guard for command strings passed to {@code sh -c}.
     * Rejects command-chaining operators that could escape the intended command.
     * This is supplementary to the pattern blacklist above.
     */
    public static List<String> validateShellCommand(String command) {
        List<String> warnings = new ArrayList<>();
        if (command == null) {
            return warnings;
        }
        // Reject patterns that allow chaining additional commands.
        Pattern[] injectionPatterns = {
                Pattern.compile(";\\s*(rm|mkfs|dd|chmod|curl|wget)"),
                Pattern.compile("\\|\\s*(bash|sh)\\b"),
                Pattern.compile("&&\\s*(rm|mkfs|dd|chmod)"),
                Pattern.compile("`.*`"),
                Pattern.compile("\\$\\(.*\\)"),
        };
        for (Pattern p : injectionPatterns) {
            if (p.matcher(command).find()) {
                warnings.add("Potential shell injection pattern detected: " + p);
            }
        }
        return warnings;
    }
}
