package com.memsys.eval;

import com.memsys.cli.ConversationCli;
import com.memsys.llm.LlmDtos;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvalServiceTest {

    @Test
    void runBatchEvalShouldFilterBlankQuestionsAndAggregateScores() {
        ConversationCli conversationCli = mock(ConversationCli.class);
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        MemoryStorage storage = mock(MemoryStorage.class);

        when(storage.readUserInsightsNarrative()).thenReturn("画像");
        when(conversationCli.processUserMessageTemporaryForEval("问题A")).thenReturn("无记忆A");
        when(conversationCli.processUserMessageWithMemoryForEval("问题A")).thenReturn("有记忆A");
        when(conversationCli.processUserMessageTemporaryForEval("问题B")).thenReturn("无记忆B");
        when(conversationCli.processUserMessageWithMemoryForEval("问题B")).thenReturn("有记忆B");
        when(extractionService.evaluateResponseQuality(eq("问题A"), eq("无记忆A"), eq(null)))
                .thenReturn(new LlmDtos.EvalScoreResult(4, 4, 4, 4, "a1"));
        when(extractionService.evaluateResponseQuality(eq("问题A"), eq("有记忆A"), eq("画像")))
                .thenReturn(new LlmDtos.EvalScoreResult(8, 8, 8, 8, "a2"));
        when(extractionService.evaluateResponseQuality(eq("问题B"), eq("无记忆B"), eq(null)))
                .thenReturn(new LlmDtos.EvalScoreResult(6, 6, 6, 6, "b1"));
        when(extractionService.evaluateResponseQuality(eq("问题B"), eq("有记忆B"), eq("画像")))
                .thenReturn(new LlmDtos.EvalScoreResult(9, 9, 9, 9, "b2"));

        EvalService service = new EvalService(conversationCli, extractionService, storage);
        var report = service.runBatchEval(java.util.List.of("问题A", " ", "问题B", "问题A"));

        assertThat(report.totalQuestions()).isEqualTo(2);
        assertThat(report.completedQuestions()).isEqualTo(2);
        assertThat(report.averageScoreWithoutMemory()).isEqualTo(5.0);
        assertThat(report.averageScoreWithMemory()).isEqualTo(8.5);
        assertThat(report.averageImprovementPercent()).isEqualTo(75.0);
        assertThat(report.bestQuestion()).isEqualTo("问题A");
        assertThat(report.worstQuestion()).isEqualTo("问题B");
        verify(storage, org.mockito.Mockito.times(2)).appendEvalResult(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void runSingleEvalShouldShortCircuitWhenQuestionIsBlank() {
        ConversationCli conversationCli = mock(ConversationCli.class);
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        MemoryStorage storage = mock(MemoryStorage.class);

        EvalService service = new EvalService(conversationCli, extractionService, storage);
        var result = service.runSingleEval("   ");

        assertThat(result.getQuestion()).isEmpty();
        assertThat(result.getResponseWithoutMemory()).isEmpty();
        assertThat(result.getResponseWithMemory()).isEmpty();
        assertThat(result.getTotalScoreWithoutMemory()).isEqualTo(5.0);
        assertThat(result.getTotalScoreWithMemory()).isEqualTo(5.0);

        verify(conversationCli, never()).processUserMessageTemporaryForEval(anyString());
        verify(conversationCli, never()).processUserMessageWithMemoryForEval(anyString());
        verify(storage).appendEvalResult(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void runSingleEvalShouldHandleNullResponsesAndPersistSafely() {
        ConversationCli conversationCli = mock(ConversationCli.class);
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        MemoryStorage storage = mock(MemoryStorage.class);

        when(storage.readUserInsightsNarrative()).thenReturn("");
        when(conversationCli.processUserMessageTemporaryForEval(anyString())).thenReturn(null);
        when(conversationCli.processUserMessageWithMemoryForEval(anyString())).thenReturn(null);
        when(extractionService.evaluateResponseQuality(eq("问题"), eq(""), eq(null))).thenReturn(null);
        when(extractionService.evaluateResponseQuality(eq("问题"), eq(""), eq(""))).thenReturn(null);

        EvalService service = new EvalService(conversationCli, extractionService, storage);
        var result = service.runSingleEval("问题");

        assertThat(result.getResponseWithoutMemory()).isEmpty();
        assertThat(result.getResponseWithMemory()).isEmpty();
        assertThat(result.getTotalScoreWithoutMemory()).isEqualTo(5.0);
        assertThat(result.getTotalScoreWithMemory()).isEqualTo(5.0);
        assertThat(result.getImprovementPercent()).isEqualTo(0.0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> recordCaptor = ArgumentCaptor.forClass(Map.class);
        verify(storage).appendEvalResult(recordCaptor.capture());
        Map<String, Object> persisted = recordCaptor.getValue();
        assertThat(persisted).containsEntry("response_without_memory_length", 0);
        assertThat(persisted).containsEntry("response_with_memory_length", 0);
    }

    @Test
    void runSingleEvalShouldClampOutOfRangeScores() {
        ConversationCli conversationCli = mock(ConversationCli.class);
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        MemoryStorage storage = mock(MemoryStorage.class);

        when(storage.readUserInsightsNarrative()).thenReturn("画像");
        when(conversationCli.processUserMessageTemporaryForEval(anyString())).thenReturn("无记忆回复");
        when(conversationCli.processUserMessageWithMemoryForEval(anyString())).thenReturn("有记忆回复");
        when(extractionService.evaluateResponseQuality(eq("问题"), eq("无记忆回复"), eq(null)))
                .thenReturn(new LlmDtos.EvalScoreResult(99, 0, 7, -3, "a"));
        when(extractionService.evaluateResponseQuality(eq("问题"), eq("有记忆回复"), eq("画像")))
                .thenReturn(new LlmDtos.EvalScoreResult(10, 8, 9, 11, "b"));

        EvalService service = new EvalService(conversationCli, extractionService, storage);
        var result = service.runSingleEval("问题");

        assertThat(result.getScoresWithoutMemory())
                .containsEntry("relevance", 10)
                .containsEntry("personalization", 1)
                .containsEntry("accuracy", 7)
                .containsEntry("helpfulness", 1);
        assertThat(result.getScoresWithMemory())
                .containsEntry("relevance", 10)
                .containsEntry("personalization", 8)
                .containsEntry("accuracy", 9)
                .containsEntry("helpfulness", 10);
    }
}
