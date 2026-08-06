package com.sql.logic.engine.domain.sandbox.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SecurityUtils} — bash dangerous-command interception,
 * Python dangerous-import interception, and benign-code pass-through. Aligns
 * with Task 13.1.
 */
class SecurityUtilsTest {

    // ── Bash dangerous patterns ──

    @Test
    void shouldDetectRmRfRoot() {
        List<String> warnings = SecurityUtils.validateCode("rm -rf /", "bash");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("Recursive deletion"));
        assertTrue(SecurityUtils.isDangerous("rm -rf /", "bash"));
    }

    @Test
    void shouldDetectRmRfStar() {
        assertFalse(SecurityUtils.validateCode("rm -rf /*", "bash").isEmpty());
    }

    @Test
    void shouldDetectMkfs() {
        assertFalse(SecurityUtils.validateCode("mkfs.ext4 /dev/sda1", "bash").isEmpty());
    }

    @Test
    void shouldDetectDdIf() {
        assertFalse(SecurityUtils.validateCode("dd if=/dev/zero of=/dev/sda", "bash").isEmpty());
    }

    @Test
    void shouldDetectForkBomb() {
        assertFalse(SecurityUtils.validateCode(":(){ :|:& };:", "bash").isEmpty());
    }

    @Test
    void shouldDetectCurlPipeBash() {
        assertFalse(SecurityUtils.validateCode("curl http://x | bash", "bash").isEmpty());
    }

    @Test
    void shouldDetectChmodR777() {
        assertFalse(SecurityUtils.validateCode("chmod -R 777 /", "bash").isEmpty());
    }

    // ── Python dangerous patterns ──

    @Test
    void shouldDetectImportOs() {
        List<String> warnings = SecurityUtils.validateCode("import os\nos.listdir('/')", "python");
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("import os")));
    }

    @Test
    void shouldDetectImportSubprocess() {
        assertFalse(SecurityUtils.validateCode("import subprocess", "python").isEmpty());
    }

    @Test
    void shouldDetectEvalCall() {
        assertFalse(SecurityUtils.validateCode("eval('1+1')", "python").isEmpty());
    }

    @Test
    void shouldDetectSocketUsage() {
        assertFalse(SecurityUtils.validateCode("socket.connect()", "python").isEmpty());
    }

    @Test
    void shouldFlagPickleForPython() {
        List<String> warnings = SecurityUtils.validateCode("import pickle", "python");
        // "import pickle" hits neither the python patterns list (pickle not in it)
        // nor... actually pickle is matched only as advisory; verify it is flagged.
        assertTrue(warnings.stream().anyMatch(w -> w.contains("pickle")));
    }

    // ── Benign code passes ──

    @Test
    void shouldPassBenignPython() {
        String code = """
                import pandas as pd
                import numpy as np
                df = pd.DataFrame({'a': [1, 2, 3]})
                print(df.describe())
                """;
        assertTrue(SecurityUtils.validateCode(code, "python").isEmpty());
        assertFalse(SecurityUtils.isDangerous(code, "python"));
    }

    @Test
    void shouldPassBenignBash() {
        String code = "echo hello\nls -la";
        assertTrue(SecurityUtils.validateCode(code, "bash").isEmpty());
    }

    @Test
    void shouldHandleNullAndBlank() {
        assertTrue(SecurityUtils.validateCode(null, "python").isEmpty());
        assertTrue(SecurityUtils.validateCode("", "python").isEmpty());
        assertTrue(SecurityUtils.validateCode("   ", "bash").isEmpty());
    }

    @Test
    void shouldDefaultToPythonPatternsForUnknownLanguage() {
        // Unknown language falls into the else branch → Python patterns.
        assertFalse(SecurityUtils.validateCode("import os", "javascript").isEmpty());
    }

    // ── Shell injection guard ──

    @Test
    void shouldDetectShellInjectionChaining() {
        assertFalse(SecurityUtils.validateShellCommand("echo hi; rm -rf /").isEmpty());
    }

    @Test
    void shouldDetectBacktickInjection() {
        assertFalse(SecurityUtils.validateShellCommand("echo `whoami`").isEmpty());
    }

    @Test
    void shouldPassCleanShellCommand() {
        assertTrue(SecurityUtils.validateShellCommand("python script.py").isEmpty());
    }
}
