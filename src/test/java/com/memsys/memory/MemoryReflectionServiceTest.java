package com.memsys.memory;

import com.memsys.llm.LlmDtos.MemoryReflectionResult;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.model.ReflectionResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryReflectionServiceTest {

    @Test
    void reflectShouldNormalizeReasonAndPurposesWhenNeedsMemory() {
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.reflectMemoryNeed(anyString())).thenReturn(
                new MemoryReflectionResult(
                        true,
                        " ",
                        Arrays.asList(" continuity ", "EXPERIENCE", "invalid", null, "continuity")
                )
        );

        MemoryReflectionService service = new MemoryReflectionService(extractionService);
        ReflectionResult result = service.reflect("继续上次的话题", "上次在讨论毕设");

        assertThat(result.needs_memory()).isTrue();
        assertThat(result.reason()).isEqualTo("需要调用长期记忆以保证回答质量。");
        assertThat(result.evidence_purposes()).containsExactly("continuity", "experience");
    }

    @Test
    void reflectShouldClearPurposesWhenNeedsMemoryIsFalseAndProvideDefaultReason() {
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.reflectMemoryNeed(anyString())).thenReturn(
                new MemoryReflectionResult(
                        false,
                        null,
                        List.of("continuity", "personalization")
                )
        );

        MemoryReflectionService service = new MemoryReflectionService(extractionService);
        ReflectionResult result = service.reflect("你好", "");

        assertThat(result.needs_memory()).isFalse();
        assertThat(result.reason()).isEqualTo("当前问题可直接回答，无需调用长期记忆。");
        assertThat(result.evidence_purposes()).isEmpty();
    }

    @Test
    void reflectShouldFallbackToContinuityPurposeWhenNeedsMemoryButPurposesInvalid() {
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.reflectMemoryNeed(anyString())).thenReturn(
                new MemoryReflectionResult(
                        true,
                        "需要历史信息",
                        List.of("INVALID", " ", "unknown")
                )
        );

        MemoryReflectionService service = new MemoryReflectionService(extractionService);
        ReflectionResult result = service.reflect("继续上次任务", "上次在讨论发布计划");

        assertThat(result.needs_memory()).isTrue();
        assertThat(result.evidence_purposes()).containsExactly("continuity");
    }
}
