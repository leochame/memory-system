package com.memsys.cli;

import org.junit.jupiter.api.Test;

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
}
