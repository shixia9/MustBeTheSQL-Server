package com.sql.logic.engine.domain.sandbox.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PathUtils} — Phase 4 path traversal prevention.
 */
class PathUtilsTest {

    @Test
    void shouldAcceptBareFilename() {
        assertEquals("/workspace/chart.png",
                PathUtils.ensureSafePath("chart.png", "/workspace"));
    }

    @Test
    void shouldAcceptSubdirectoryPath() {
        assertEquals("/workspace/output/result.csv",
                PathUtils.ensureSafePath("output/result.csv", "/workspace"));
    }

    @Test
    void shouldRejectParentTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafePath("../../etc/passwd", "/workspace"));
    }

    @Test
    void shouldRejectAbsolutePathOutsideBase() {
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafePath("/etc/passwd", "/workspace"));
    }

    @Test
    void shouldRejectBlankPath() {
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafePath("", "/workspace"));
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafePath(null, "/workspace"));
    }

    @Test
    void shouldRejectBlankBaseDir() {
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafePath("file.txt", ""));
    }

    // ---- ensureSafeFilename ----

    @Test
    void shouldAcceptBareFilenameOnly() {
        assertEquals("chart.png", PathUtils.ensureSafeFilename("chart.png"));
    }

    @Test
    void shouldRejectFilenameWithSlash() {
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafeFilename("dir/file.txt"));
    }

    @Test
    void shouldRejectFilenameWithBackslash() {
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafeFilename("dir\\file.txt"));
    }

    @Test
    void shouldRejectParentDotDot() {
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafeFilename("../passwd"));
    }

    @Test
    void shouldRejectDotPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafeFilename(".env"));
    }

    @Test
    void shouldRejectBlankFilename() {
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafeFilename(""));
        assertThrows(IllegalArgumentException.class,
                () -> PathUtils.ensureSafeFilename(null));
    }
}
