package com.sql.logic.engine.domain.sandbox.util;

/**
 * Path traversal prevention.
 *
 * <p>Validates that a user-supplied filename resolves to a path inside the
 * sandbox working directory, preventing {@code ../../etc/passwd} style attacks
 * against {@code get-file} and file-based operations.
 */
public final class PathUtils {

    private PathUtils() {
    }

    /**
     * Ensure that {@code path} resolves to a location inside {@code baseDir}.
     * Normalises both paths, collapses {@code ..} / {@code .} segments, and
     * verifies the result starts with {@code baseDir}.
     *
     * @param path    the user-supplied path (e.g. a filename from a REST query param)
     * @param baseDir the allowed root directory (e.g. {@code /workspace})
     * @return the normalised absolute path if safe
     * @throws IllegalArgumentException if the path escapes {@code baseDir}
     */
    public static String ensureSafePath(String path, String baseDir) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        if (baseDir == null || baseDir.isBlank()) {
            throw new IllegalArgumentException("Base directory must not be blank");
        }

        // Normalise: resolve against baseDir, collapse .. and . segments.
        // We use java.nio.file.Path for canonical resolution without touching the
        // filesystem (the path is inside a container, not the host).
        java.nio.file.Path basePath = java.nio.file.Paths.get(baseDir).normalize();
        java.nio.file.Path resolved = basePath.resolve(path).normalize();

        // Reject if the resolved path is outside the base directory.
        // startsWith works on Path segments, so "/workspace/file" startsWith "/work"
        // is false (segment boundary), but "/workspace/file" startsWith "/workspace" is true.
        if (!resolved.startsWith(basePath)) {
            throw new IllegalArgumentException(
                    "Path traversal detected: '" + path + "' resolves outside '" + baseDir + "'");
        }

        // Sandbox paths are container paths (always POSIX), so never leak the host
        // OS separator (e.g. '\workspace\chart.png' on Windows).
        return resolved.toString().replace('\\', '/');
    }

    /**
     * Extract the bare filename from a path, rejecting any directory separators.
     * Use this for simple filename validation where no subdirectories are allowed.
     *
     * @param filename the user-supplied filename
     * @return the validated filename
     * @throws IllegalArgumentException if the filename contains separators or {@code ..}
     */
    public static String ensureSafeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        // Reject any path separator or parent-directory reference.
        if (filename.contains("/") || filename.contains("\\")
                || filename.contains("..") || filename.startsWith(".")) {
            throw new IllegalArgumentException(
                    "Unsafe filename: '" + filename + "' (must be a bare filename, no paths)");
        }
        return filename;
    }
}
