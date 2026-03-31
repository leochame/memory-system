package com.memsys.cli;

import com.memsys.memory.model.MemoryEvidenceTrace;
import com.memsys.memory.model.ReflectionResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliRunnerTest {

    @Test
    void describeNeedsMemoryLabelShouldUseTriStateText() {
        assertEquals("需要记忆", CliRunner.describeNeedsMemoryLabel("是"));
        assertEquals("不需要记忆", CliRunner.describeNeedsMemoryLabel("否"));
        assertEquals("未知", CliRunner.describeNeedsMemoryLabel("unknown"));
        assertEquals("未知", CliRunner.describeNeedsMemoryLabel(null));
    }

    @Test
    void needsMemoryLabelFromRawShouldTreatInvalidValueAsUnknown() {
        assertEquals("unknown", CliRunner.needsMemoryLabelFromRaw(null));
        assertEquals("是", CliRunner.needsMemoryLabelFromRaw(Boolean.TRUE));
        assertEquals("否", CliRunner.needsMemoryLabelFromRaw(Boolean.FALSE));
        assertEquals("是", CliRunner.needsMemoryLabelFromRaw("true"));
        assertEquals("否", CliRunner.needsMemoryLabelFromRaw("0"));
        assertEquals("unknown", CliRunner.needsMemoryLabelFromRaw("null"));
        assertEquals("unknown", CliRunner.needsMemoryLabelFromRaw("N/A"));
    }

    @Test
    void traceEvidenceTypesShouldJoinAndDeduplicateValues() {
        MemoryEvidenceTrace trace = new MemoryEvidenceTrace(
                LocalDateTime.of(2026, 3, 31, 23, 50),
                "继续排查",
                new ReflectionResult(
                        true,
                        "CONTINUITY",
                        "需要历史上下文",
                        0.9d,
                        "优先检索最近记录",
                        List.of(" USER_INSIGHT ", "RECENT_HISTORY", "USER_INSIGHT", "", " "),
                        List.of("continuity")
                ),
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        );

        assertEquals("USER_INSIGHT, RECENT_HISTORY", CliRunner.traceEvidenceTypes(trace));
    }

    @Test
    void traceEvidencePurposesShouldJoinAndDeduplicateValues() {
        MemoryEvidenceTrace trace = new MemoryEvidenceTrace(
                LocalDateTime.of(2026, 3, 31, 23, 51),
                "继续排查",
                new ReflectionResult(
                        true,
                        "CONTINUITY",
                        "需要历史上下文",
                        0.9d,
                        "优先检索最近记录",
                        List.of("USER_INSIGHT"),
                        List.of(" continuity ", "experience", "continuity", "", " ")
                ),
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                ""
        );

        assertEquals("continuity, experience", CliRunner.traceEvidencePurposes(trace));
    }

    @Test
    void previewUnusedEvidenceShouldReturnDeduplicatedPreview() {
        String preview = CliRunner.previewUnusedEvidence(
                List.of(" i1 ", "i2", "i2", "i3", ""),
                List.of("i2", " "),
                2
        );
        assertEquals("i1; i3", preview);
    }

    @Test
    void previewUnusedEvidenceShouldAppendOverflowCount() {
        String preview = CliRunner.previewUnusedEvidence(
                List.of("a", "b", "c"),
                List.of(),
                2
        );
        assertEquals("a; b ... +1", preview);
    }
}
