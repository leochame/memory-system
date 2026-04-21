package com.memsys.memory;

import com.memsys.llm.LlmDtos;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationSummaryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void generateAndPersistSummaryShouldWriteTopicAndMilestoneRecordsForTopicShift() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        ConversationSummaryService service = new ConversationSummaryService(extractionService, storage, 20);

        LocalDateTime base = LocalDateTime.of(2026, 4, 8, 10, 0);
        storage.appendToHistory("user", "我们先整理 benchmark 指标", base.plusMinutes(1));
        storage.appendToHistory("assistant", "可以，先定义准确率和召回率", base.plusMinutes(2));
        storage.appendToHistory("user", "接着把记忆治理一起收口", base.plusMinutes(3));
        storage.appendToHistory("assistant", "我会补治理命令和状态展示", base.plusMinutes(4));

        service.onTurnCompleted();
        service.onTurnCompleted();
        service.onTurnCompleted();
        service.onTurnCompleted();

        when(extractionService.summarizeConversation(anyString(), anyInt()))
                .thenReturn(new LlmDtos.ConversationSummaryResult(
                        "本轮完成 benchmark 基线定义，并决定收口记忆治理展示。",
                        List.of("benchmark", "记忆治理", "阶段收口"),
                        4,
                        "2026-04-08 10:01 ~ 10:04"
                ));
        when(extractionService.detectTopicShift(anyString(), anyString()))
                .thenReturn(new LlmDtos.TopicShiftDetectionResult(true, "benchmark", "记忆治理"));

        String summary = service.checkTopicShiftAndSummarize("最近在讨论 benchmark 指标", "现在切到记忆治理收口");

        assertThat(summary).contains("完成 benchmark 基线定义");
        assertThat(storage.readSessionSummaries(10)).hasSize(1);
        assertThat(storage.readTopicSummaries(10)).singleElement()
                .satisfies(record -> {
                    assertThat(record.get("topic_label")).isEqualTo("记忆治理");
                    assertThat(record.get("summary_type")).isEqualTo("topic");
                });
        assertThat(storage.readMilestoneSummaries(10)).singleElement()
                .satisfies(record -> {
                    assertThat(record.get("milestone_label")).isEqualTo("benchmark / 记忆治理");
                    assertThat(record.get("summary_type")).isEqualTo("milestone");
                });
    }

    @Test
    void generateAndPersistSummaryShouldOnlyWriteSessionSummaryWhenNoSpecialSignal() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        ConversationSummaryService service = new ConversationSummaryService(extractionService, storage, 2);

        LocalDateTime base = LocalDateTime.of(2026, 4, 8, 11, 0);
        storage.appendToHistory("user", "继续解释这个概念", base.plusMinutes(1));
        storage.appendToHistory("assistant", "这里重点是上下文压缩", base.plusMinutes(2));

        service.onTurnCompleted();
        service.onTurnCompleted();

        when(extractionService.summarizeConversation(anyString(), anyInt()))
                .thenReturn(new LlmDtos.ConversationSummaryResult(
                        "本轮主要围绕一个概念做了连续解释。",
                        List.of("上下文压缩"),
                        2,
                        "2026-04-08 11:01 ~ 11:02"
                ));

        String summary = service.generateAndPersistSummary();

        assertThat(summary).contains("连续解释");
        assertThat(storage.readSessionSummaries(10)).hasSize(1);
        assertThat(storage.readTopicSummaries(10)).isEmpty();
        assertThat(storage.readMilestoneSummaries(10)).isEmpty();
    }
}
