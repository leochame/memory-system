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
                        "CONTINUITY",
                        " ",
                        85d,
                        "",
                        List.of(" recent_history ", "invalid"),
                        Arrays.asList(" continuity ", "EXPERIENCE", "invalid", null, "continuity")
                )
        );

        MemoryReflectionService service = new MemoryReflectionService(extractionService);
        ReflectionResult result = service.reflect("继续上次的话题", "上次在讨论毕设");

        assertThat(result.needs_memory()).isTrue();
        assertThat(result.memory_purpose()).isEqualTo("CONTINUITY");
        assertThat(result.reason()).isEqualTo("需要调用长期记忆以保证回答质量。");
        assertThat(result.confidence()).isEqualTo(0.85d);
        assertThat(result.retrieval_hint()).isEqualTo("优先检索与用户当前问题最相关的历史证据。");
        assertThat(result.evidence_types()).containsExactly("RECENT_HISTORY");
        assertThat(result.evidence_purposes()).containsExactly("continuity", "experience");
    }

    @Test
    void reflectShouldClearPurposesWhenNeedsMemoryIsFalseAndProvideDefaultReason() {
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.reflectMemoryNeed(anyString())).thenReturn(
                new MemoryReflectionResult(
                        false,
                        "PERSONALIZATION",
                        null,
                        null,
                        "should be cleared",
                        List.of("USER_INSIGHT"),
                        List.of("continuity", "personalization")
                )
        );

        MemoryReflectionService service = new MemoryReflectionService(extractionService);
        ReflectionResult result = service.reflect("你好", "");

        assertThat(result.needs_memory()).isFalse();
        assertThat(result.memory_purpose()).isEqualTo("NOT_NEEDED");
        assertThat(result.reason()).isEqualTo("当前问题可直接回答，无需调用长期记忆。");
        assertThat(result.retrieval_hint()).isBlank();
        assertThat(result.evidence_types()).isEmpty();
        assertThat(result.evidence_purposes()).isEmpty();
    }

    @Test
    void reflectShouldFallbackToContinuityPurposeWhenNeedsMemoryButPurposesInvalid() {
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.reflectMemoryNeed(anyString())).thenReturn(
                new MemoryReflectionResult(
                        true,
                        "UNKNOWN",
                        "需要历史信息",
                        120d,
                        " ",
                        List.of("INVALID"),
                        List.of("INVALID", " ", "unknown")
                )
        );

        MemoryReflectionService service = new MemoryReflectionService(extractionService);
        ReflectionResult result = service.reflect("继续上次任务", "上次在讨论发布计划");

        assertThat(result.needs_memory()).isTrue();
        assertThat(result.memory_purpose()).isEqualTo("CONTINUITY");
        assertThat(result.evidence_types()).containsExactly("USER_INSIGHT", "RECENT_HISTORY");
        assertThat(result.confidence()).isEqualTo(1.0d);
        assertThat(result.evidence_purposes()).containsExactly("continuity");
    }

    @Test
    void reflectShouldFixContradictingNotNeededPurposeWhenNeedsMemoryIsTrue() {
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.reflectMemoryNeed(anyString())).thenReturn(
                new MemoryReflectionResult(
                        true,
                        "NOT_NEEDED",
                        "需要历史上下文",
                        88d,
                        "优先看最近上下文",
                        List.of("RECENT_HISTORY"),
                        List.of("continuity")
                )
        );

        MemoryReflectionService service = new MemoryReflectionService(extractionService);
        ReflectionResult result = service.reflect("继续上次任务", "上次讨论了部署计划");

        assertThat(result.needs_memory()).isTrue();
        assertThat(result.memory_purpose()).isEqualTo("CONTINUITY");
        assertThat(result.reason()).isEqualTo("需要历史上下文");
    }

    @Test
    void reflectShouldFallbackForNullLikeReasonAndHint() {
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.reflectMemoryNeed(anyString())).thenReturn(
                new MemoryReflectionResult(
                        true,
                        "CONTINUITY",
                        " null ",
                        80d,
                        "N/A",
                        List.of("RECENT_HISTORY"),
                        List.of("continuity")
                )
        );

        MemoryReflectionService service = new MemoryReflectionService(extractionService);
        ReflectionResult result = service.reflect("继续上次任务", "上次讨论了部署计划");

        assertThat(result.reason()).isEqualTo("需要调用长期记忆以保证回答质量。");
        assertThat(result.retrieval_hint()).isEqualTo("优先检索与用户当前问题最相关的历史证据。");
    }

    @Test
    void reflectShouldFallbackToDefaultConfidenceWhenConfidenceNotFinite() {
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.reflectMemoryNeed(anyString())).thenReturn(
                new MemoryReflectionResult(
                        true,
                        "CONTINUITY",
                        "需要历史信息",
                        Double.NaN,
                        "优先看最近上下文",
                        List.of("RECENT_HISTORY"),
                        List.of("continuity")
                )
        );

        MemoryReflectionService service = new MemoryReflectionService(extractionService);
        ReflectionResult result = service.reflect("继续上次任务", "上次讨论了部署计划");

        assertThat(result.confidence()).isEqualTo(0.7d);
    }
}
