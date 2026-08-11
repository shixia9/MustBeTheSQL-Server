package com.sql.logic.engine.domain.agentic.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ComplexityLevelTest {

    @Test
    void simpleShouldIdentifyAsSimple() {
        ComplexityAssessment a = new ComplexityAssessment(ComplexityLevel.SIMPLE, "test", null);
        assertTrue(a.isSimple());
        assertFalse(a.needsClarification());
    }

    @Test
    void clarifyShouldIdentifyAsNeedingClarification() {
        ComplexityAssessment a = new ComplexityAssessment(ComplexityLevel.CLARIFY, "ambiguous", null);
        assertTrue(a.needsClarification());
        assertFalse(a.isSimple());
    }

    @Test
    void mediumShouldNotBeSimple() {
        ComplexityAssessment a = new ComplexityAssessment(ComplexityLevel.MEDIUM, "", null);
        assertFalse(a.isSimple());
        assertFalse(a.needsClarification());
    }

    @Test
    void complexShouldNotBeSimple() {
        ComplexityAssessment a = new ComplexityAssessment(ComplexityLevel.COMPLEX, "", null);
        assertFalse(a.isSimple());
        assertFalse(a.needsClarification());
    }

    @Test
    void allLevelsShouldHaveNames() {
        for (ComplexityLevel level : ComplexityLevel.values()) {
            assertNotNull(level.name());
            assertFalse(level.name().isBlank());
        }
    }

    @Test
    void assessmentShouldStoreRawResponse() {
        String raw = "{\"complexity\":\"SIMPLE\",\"reason\":\"single table query\"}";
        ComplexityAssessment a = new ComplexityAssessment(ComplexityLevel.SIMPLE, "single table query", raw);
        assertEquals("single table query", a.reason());
        assertEquals(raw, a.rawResponse());
    }
}
