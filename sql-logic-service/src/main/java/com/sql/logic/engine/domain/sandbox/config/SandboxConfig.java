package com.sql.logic.engine.domain.sandbox.config;

import java.util.Map;

/**
 * Sandbox static configuration constants.
 *
 * <p>Holds the immutable language-to-image mapping, language-to-command mapping,
 * file-extension mapping, and global resource-limit constants.
 */
public final class SandboxConfig {

    private SandboxConfig() {
    }

    // ---- Language → Docker image ----

    public static final Map<String, String> LANGUAGE_IMAGES = Map.of(
            "python", "python:3.11-slim",
            "python-vnc", "vnc-gui-browser:latest",
            "javascript", "node:18-slim",
            "java", "openjdk:11-jre-slim",
            "cpp", "gcc:latest",
            "go", "golang:1.21-alpine",
            "rust", "rust:1.75-slim"
    );

    /** Default working directory inside the sandbox. */
    public static final String WORKING_DIR = "/workspace";

    // ---- Global resource limits ----

    /** 256 MB — default memory limit per session. */
    public static final long MAX_MEMORY = 256L * 1024 * 1024;

    /** 50% — default CPU percent ceiling (advisory; not enforced in Docker CLI mode). */
    public static final double MAX_CPU_PERCENT = 50.0;

    /** 30 seconds — default per-execution timeout. */
    public static final int MAX_EXECUTION_TIME = 30;

    /** 10 MB — max single file size for {@code getFileContent()}. */
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    /** 300 seconds — max dependency install timeout. */
    public static final int MAX_DEPENDENCY_INSTALL_TIME = 300;

    /** 200 MB — max total dependency install size. */
    public static final long MAX_DEPENDENCY_INSTALL_SIZE = 200L * 1024 * 1024;

    /** 10 — max processes (pids) inside the sandbox. */
    public static final int MAX_PROCESSES = 10;

    // ---- Language helpers ----

    /**
     * Get the Docker image for a language. Falls back to {@code python:3.11-slim}.
     */
    public static String getImageByLanguage(String language) {
        if (language == null) {
            return "python:3.11-slim";
        }
        return LANGUAGE_IMAGES.getOrDefault(language.toLowerCase(), "python:3.11-slim");
    }

    /**
     * Get the execution command for a language + filename. The returned command
     * is run inside the sandbox working directory via {@code docker exec sh -c "..."}
     * or {@code ProcessBuilder}.
     *
     * @param language the language key
     * @param filename the code filename (e.g. "abc123_1690000000.py")
     * @return the shell command string, or {@code "cat {filename}"} if unknown
     */
    public static String getCommandByLanguage(String language, String filename) {
        if (language == null) {
            return "cat " + filename;
        }
        return switch (language.toLowerCase()) {
            case "python", "python-vnc" -> "python " + filename;
            case "javascript" -> "node " + filename;
            case "java" -> "javac " + filename + " && java " + stripExtension(filename);
            case "cpp" -> "g++ -o program " + filename + " && ./program";
            case "go" -> "go run " + filename;
            case "rust" -> "rustc " + filename + " -o program && ./program";
            case "bash", "shell", "sh" -> "bash " + filename;
            default -> "cat " + filename;
        };
    }

    /** Get the source file extension for a language. */
    public static String getFileExtension(String language) {
        if (language == null) {
            return ".txt";
        }
        return switch (language.toLowerCase()) {
            case "python", "python-vnc" -> ".py";
            case "javascript" -> ".js";
            case "java" -> ".java";
            case "cpp" -> ".cpp";
            case "go" -> ".go";
            case "rust" -> ".rs";
            case "bash", "shell", "sh" -> ".sh";
            default -> ".txt";
        };
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
