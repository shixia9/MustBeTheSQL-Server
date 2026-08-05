package com.sql.logic.engine.domain.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PythonAstValidator} — blacklisted imports, dynamic
 * execution, write-mode open(), string-literal false-positive avoidance, and
 * benign-code pass-through. Aligns with Task 13.2.
 */
class PythonAstValidatorTest {

    @Test
    void shouldDetectDirectBlacklistedImport() {
        List<String> v = PythonAstValidator.validate("import os\nos.getcwd()");
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("import os"));
        assertTrue(PythonAstValidator.isViolating("import subprocess"));
    }

    @Test
    void shouldDetectFromBlacklistedImport() {
        List<String> v = PythonAstValidator.validate("from socket import socket");
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("from socket"));
    }

    @Test
    void shouldDetectMultipleBlacklistedImports() {
        List<String> v = PythonAstValidator.validate("import os\nimport subprocess\nimport pickle");
        assertEquals(3, v.size());
    }

    @Test
    void shouldAllowImportSys() {
        // sys is required by the sandbox stdin/stdout protocol (json.load(sys.stdin),
        // traceback.print_exc(file=sys.stderr)) — it must pass validation.
        String code = "import sys\n"
                + "import json\n"
                + "data = json.load(sys.stdin)\n"
                + "print(json.dumps(data))\n";
        assertTrue(PythonAstValidator.validate(code).isEmpty());
    }

    @Test
    void shouldDetectDunderImport() {
        List<String> v = PythonAstValidator.validate("mod = __import__('os')");
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("__import__"));
    }

    @Test
    void shouldDetectEvalCall() {
        assertTrue(PythonAstValidator.isViolating("result = eval('1+1')"));
    }

    @Test
    void shouldDetectExecCall() {
        List<String> v = PythonAstValidator.validate("exec('print(1)')");
        assertEquals(1, v.size());
        assertTrue(v.get(0).contains("exec()"));
    }

    @Test
    void shouldDetectOpenWriteMode() {
        assertTrue(PythonAstValidator.isViolating("f = open('/etc/passwd', 'w')"));
        assertTrue(PythonAstValidator.isViolating("open('x.txt', 'a')"));
    }

    @Test
    void shouldAllowOpenReadMode() {
        // read mode ('r') is not flagged by OPEN_WRITE pattern.
        List<String> v = PythonAstValidator.validate("f = open('data.csv', 'r')");
        // Only flag if a blacklisted import/call is present — open(...,'r') is allowed.
        assertFalse(v.stream().anyMatch(s -> s.contains("open()")));
    }

    @Test
    void shouldNotFlagBlacklistedImportInsideStringLiteral() {
        // A string literal containing "import os" must NOT trigger a false positive.
        String code = "msg = \"do not import os here\"\nprint(msg)";
        List<String> v = PythonAstValidator.validate(code);
        assertFalse(v.stream().anyMatch(s -> s.contains("import os")));
    }

    @Test
    void shouldNotFlagBlacklistedImportInsideComment() {
        String code = "# remember to import os for prod\ncount = 1";
        List<String> v = PythonAstValidator.validate(code);
        assertFalse(v.stream().anyMatch(s -> s.contains("import os")));
    }

    @Test
    void shouldPassBenignDataAnalysisCode() {
        String code = "import pandas as pd\n"
                + "import numpy as np\n"
                + "import matplotlib.pyplot as plt\n"
                + "df = pd.read_csv('data.csv')\n"
                + "print(df.head())\n"
                + "plt.bar(df['x'], df['y'])\n"
                + "plt.savefig('out.png')\n";
        // plt.savefig opens a file internally but the source has no open(..,'w'),
        // no blacklisted import, no eval/exec → clean.
        List<String> v = PythonAstValidator.validate(code);
        assertTrue(v.isEmpty(), "benign code should be clean, got: " + v);
    }

    @Test
    void shouldHandleNullAndBlank() {
        assertTrue(PythonAstValidator.validate(null).isEmpty());
        assertTrue(PythonAstValidator.validate("").isEmpty());
        assertTrue(PythonAstValidator.validate("   ").isEmpty());
    }
}
