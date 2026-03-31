package com.memsys.memory;

import com.memsys.memory.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryTraceInsightServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void analyzeRecentTracesShouldReturnDefaultSuggestionWhenNoTrace() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.requestedLimit()).isEqualTo(20);
        assertThat(report.sampleSize()).isZero();
        assertThat(report.suggestions()).isNotEmpty();
        assertThat(report.suggestions().get(0)).contains("暂无证据历史");
        assertThat(report.trendSummary().available()).isFalse();
    }

    @Test
    void analyzeRecentTracesShouldAggregateEvidenceUsage() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        storage.appendMemoryEvidenceTrace(trace(
                true,
                true,
                "continuity",
                List.of("insight-a", "insight-b"),
                List.of("insight-a"),
                List.of("example-a"),
                List.of("example-a"),
                List.of("debugging"),
                List.of("debugging"),
                List.of("[到期] 周报"),
                List.of("[到期] 周报")
        ));
        storage.appendMemoryEvidenceTrace(trace(
                true,
                false,
                "personalization",
                List.of("insight-c"),
                List.of(),
                List.of(),
                List.of(),
                List.of("coding"),
                List.of(),
                List.of(),
                List.of()
        ));

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(50);

        assertThat(report.sampleSize()).isEqualTo(2);
        assertThat(report.memoryLoadedRate()).isEqualTo(1.0d);
        assertThat(report.needsMemoryRate()).isEqualTo(0.5d);
        assertThat(report.unknownNeedsMemoryRate()).isZero();
        assertThat(report.insightStat().retrieved()).isEqualTo(3);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(1);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).isNotEmpty();
        assertThat(report.topUsedSkills().get(0)).contains("debugging");
        assertThat(report.topPurposeInsights()).isNotEmpty();
        assertThat(report.topPurposeInsights().get(0).purpose()).isEqualTo("continuity");
        assertThat(report.topPurposeInsights().get(0).memoryLoadedRate()).isEqualTo(1.0d);
        assertThat(report.topPurposeInsights().get(0).insightUsageRate()).isEqualTo(0.5d);
    }

    @Test
    void analyzeRecentTracesShouldExposeTrendAndUnknownRate() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        // 前半窗口：insight 使用率高（100%）
        storage.appendMemoryEvidenceTrace(trace(true, true, "continuity",
                List.of("i1"), List.of("i1"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        storage.appendMemoryEvidenceTrace(trace(true, true, "continuity",
                List.of("i2"), List.of("i2"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        storage.appendMemoryEvidenceTrace(trace(true, true, "continuity",
                List.of("i3"), List.of("i3"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        // 后半窗口：insight 使用率降低（0%），并插入一个缺失 reflection.needs_memory 的样本
        storage.appendMemoryEvidenceTrace(trace(true, false, "personalization",
                List.of("i4"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        storage.appendMemoryEvidenceTrace(trace(true, false, "personalization",
                List.of("i5"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        Map<String, Object> missingNeedsMemory = trace(true, false, "personalization",
                List.of("i6"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        ((Map<?, ?>) missingNeedsMemory.get("reflection")).remove("needs_memory");
        storage.appendMemoryEvidenceTrace(missingNeedsMemory);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(50);

        assertThat(report.sampleSize()).isEqualTo(6);
        assertThat(report.unknownNeedsMemoryRate()).isGreaterThan(0d);
        assertThat(report.trendSummary().available()).isTrue();
        assertThat(report.trendSummary().insightUsageTrend().delta()).isLessThan(0d);
        assertThat(report.topPurposeInsights()).isNotEmpty();
        assertThat(report.topPurposeInsights().get(0).sampleSize()).isGreaterThan(0);
    }

    @Test
    void analyzeRecentTracesShouldParseAliasAndStringifiedTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memoryLoaded", "yes");
        trace.put("reflectionResult",
                "{\"needsMemory\":\"y\",\"reason\":\"需要追踪任务进展\",\"evidencePurposes\":\"[\\\"followup\\\"]\"}");
        trace.put("retrievedInsights", "[\"insight-a\",\"insight-b\"]");
        trace.put("usedInsights", "[\"insight-a\"]");
        trace.put("retrievedExamples", "[]");
        trace.put("usedExamples", "[]");
        trace.put("retrievedSkills", "\"[\\\"skill-a\\\",\\\"skill-b\\\"]\"");
        trace.put("usedSkills", "[\"skill-a\"]");
        trace.put("retrievedTasks", "[\"task-a\"]");
        trace.put("usedTasks", "[]");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.memoryLoadedRate()).isEqualTo(1.0d);
        assertThat(report.needsMemoryRate()).isEqualTo(1.0d);
        assertThat(report.unknownNeedsMemoryRate()).isZero();
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.topPurposes()).isNotEmpty();
        assertThat(report.topPurposes().get(0)).contains("followup");
    }

    @Test
    void analyzeRecentTracesShouldNormalizeAndDeduplicatePurposesPerTrace() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> reflection = new LinkedHashMap<>();
        reflection.put("needs_memory", true);
        reflection.put("reason", "需要跟进");
        reflection.put("evidence_purposes", List.of("FollowUp", " followup ", "FOLLOWUP"));

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection", reflection);
        trace.put("retrieved_insights", List.of("i-1"));
        trace.put("used_insights", List.of("i-1"));
        trace.put("retrieved_examples", List.of());
        trace.put("used_examples", List.of());
        trace.put("loaded_skills", List.of());
        trace.put("used_skills", List.of());
        trace.put("retrieved_tasks", List.of());
        trace.put("used_tasks", List.of());
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.topPurposes()).containsExactly("followup (1)");
        assertThat(report.topPurposeInsights()).hasSize(1);
        assertThat(report.topPurposeInsights().get(0).purpose()).isEqualTo("followup");
        assertThat(report.topPurposeInsights().get(0).sampleSize()).isEqualTo(1);
    }

    private Map<String, Object> trace(boolean memoryLoaded,
                                      boolean needsMemory,
                                      String purpose,
                                      List<String> retrievedInsights,
                                      List<String> usedInsights,
                                      List<String> retrievedExamples,
                                      List<String> usedExamples,
                                      List<String> loadedSkills,
                                      List<String> usedSkills,
                                      List<String> retrievedTasks,
                                      List<String> usedTasks) {
        Map<String, Object> reflection = new LinkedHashMap<>();
        reflection.put("needs_memory", needsMemory);
        reflection.put("reason", needsMemory ? "需要历史支持" : "当前消息足够");
        reflection.put("evidence_purposes", List.of(purpose));

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", memoryLoaded);
        trace.put("reflection", reflection);
        trace.put("retrieved_insights", retrievedInsights);
        trace.put("used_insights", usedInsights);
        trace.put("retrieved_examples", retrievedExamples);
        trace.put("used_examples", usedExamples);
        trace.put("loaded_skills", loadedSkills);
        trace.put("used_skills", usedSkills);
        trace.put("retrieved_tasks", retrievedTasks);
        trace.put("used_tasks", usedTasks);
        return trace;
    }
}
