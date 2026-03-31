package com.memsys.memory.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReflectionResultTest {

    @Test
    void normalizeMemoryPurposeShouldSupportHyphenSnakeAndCamelForms() {
        assertThat(ReflectionResult.normalizeMemoryPurpose("action-followup", true))
                .isEqualTo("ACTION_FOLLOWUP");
        assertThat(ReflectionResult.normalizeMemoryPurpose("ACTION_FOLLOWUP", true))
                .isEqualTo("ACTION_FOLLOWUP");
        assertThat(ReflectionResult.normalizeMemoryPurpose("actionFollowup", true))
                .isEqualTo("ACTION_FOLLOWUP");
        assertThat(ReflectionResult.normalizeMemoryPurpose("experienceReuse", true))
                .isEqualTo("EXPERIENCE_REUSE");
    }

    @Test
    void normalizeMemoryPurposeShouldFallbackToContinuityForInvalidOrContradictingInput() {
        assertThat(ReflectionResult.normalizeMemoryPurpose("not-needed", true))
                .isEqualTo("CONTINUITY");
        assertThat(ReflectionResult.normalizeMemoryPurpose("", true))
                .isEqualTo("CONTINUITY");
        assertThat(ReflectionResult.normalizeMemoryPurpose("invalid-purpose", true))
                .isEqualTo("CONTINUITY");
    }

    @Test
    void normalizeMemoryPurposeShouldForceNotNeededWhenMemoryNotRequired() {
        assertThat(ReflectionResult.normalizeMemoryPurpose("action-followup", false))
                .isEqualTo("NOT_NEEDED");
        assertThat(ReflectionResult.normalizeMemoryPurpose("CONTINUITY", false))
                .isEqualTo("NOT_NEEDED");
    }

    @Test
    void defaultEvidenceAndPurposesShouldFollowNormalizedMemoryPurpose() {
        assertThat(ReflectionResult.defaultEvidenceTypesForMemoryPurpose("action-followup"))
                .containsExactly("TASK", "RECENT_HISTORY");
        assertThat(ReflectionResult.defaultPurposesForMemoryPurpose("action-followup"))
                .containsExactly("followup");
        assertThat(ReflectionResult.defaultEvidenceTypesForMemoryPurpose("not needed"))
                .isEqualTo(List.of());
        assertThat(ReflectionResult.defaultPurposesForMemoryPurpose("not needed"))
                .isEqualTo(List.of());
    }
}
