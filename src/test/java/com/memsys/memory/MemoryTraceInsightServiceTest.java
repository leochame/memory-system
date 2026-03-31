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
    void analyzeRecentTracesShouldParseMultiLayerStringifiedTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        String reflection = "{\"needsMemory\":\"y\",\"reason\":\"multi-layer\",\"evidencePurposes\":\"[\\\"followup\\\"]\"}";
        String retrievedInsights = "[\"insight-a\",\"insight-b\"]";
        String usedInsights = "[\"insight-a\"]";
        String retrievedSkills = "[\"skill-a\",\"skill-b\"]";
        String usedSkills = "[\"skill-a\"]";

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memoryLoaded", "yes");
        trace.put("reflectionResult", quoteJsonString(quoteJsonString(reflection)));
        trace.put("retrievedInsights", quoteJsonString(quoteJsonString(retrievedInsights)));
        trace.put("usedInsights", quoteJsonString(quoteJsonString(usedInsights)));
        trace.put("retrievedExamples", quoteJsonString(quoteJsonString("[]")));
        trace.put("usedExamples", quoteJsonString(quoteJsonString("[]")));
        trace.put("retrievedSkills", quoteJsonString(quoteJsonString(retrievedSkills)));
        trace.put("usedSkills", quoteJsonString(quoteJsonString(usedSkills)));
        trace.put("retrievedTasks", quoteJsonString(quoteJsonString("[\"task-a\"]")));
        trace.put("usedTasks", quoteJsonString(quoteJsonString("[]")));
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
        assertThat(report.topPurposes()).containsExactly("followup (1)");
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

    @Test
    void analyzeRecentTracesShouldParseObjectListEvidenceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "对象数组兼容",
                "evidence_purposes", List.of("followup")
        ));
        trace.put("retrieved_insights", List.of(
                Map.of("slot_name", "diet_preference"),
                Map.of("content", "偏好简洁表达")
        ));
        trace.put("used_insights", List.of(Map.of("slotName", "diet_preference")));
        trace.put("retrieved_examples", "[{\"title\":\"答辩案例\"}]");
        trace.put("used_examples", List.of(Map.of("name", "答辩案例")));
        trace.put("retrieved_skills", List.of(Map.of("name", "debugging"), Map.of("id", "planner")));
        trace.put("used_skills", List.of(Map.of("value", "debugging")));
        trace.put("retrieved_tasks", List.of(Map.of("title", "提交周报")));
        trace.put("used_tasks", List.of(Map.of("text", "提交周报")));
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(1);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("debugging (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseNestedEvidenceGroups() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "nested evidence",
                "evidence_purposes", List.of("followup")
        ));
        trace.put("evidence", Map.of(
                "retrieved", Map.of(
                        "insights", List.of("i-1", "i-2"),
                        "examples", List.of("e-1"),
                        "skills", List.of("s-1", "s-2"),
                        "tasks", List.of("t-1")
                ),
                "used", Map.of(
                        "insights", List.of("i-1"),
                        "examples", List.of("e-1"),
                        "skills", List.of("s-1"),
                        "tasks", List.of("t-1")
                )
        ));
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(1);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-1 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseNestedEvidenceGroupsWithLegacyKeys() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "nested evidence legacy keys",
                "evidence_purposes", List.of("followup")
        ));
        trace.put("evidence", Map.of(
                "retrieved", Map.of(
                        "retrieved_insights", List.of("i-1", "i-2"),
                        "retrieved_examples", List.of("e-1"),
                        "loaded_skills", List.of("s-1"),
                        "retrieved_tasks", List.of("t-1")
                ),
                "used", Map.of(
                        "used_insights", List.of("i-1"),
                        "used_examples", List.of("e-1"),
                        "used_skills", List.of("s-1"),
                        "used_tasks", List.of("t-1")
                )
        ));
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(1);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(1);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-1 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedDotPathTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection.needs_memory", true);
        trace.put("reflection.reason", "flat key trace");
        trace.put("reflection.evidence_purposes", List.of("follow-up"));
        trace.put("evidence.retrieved.insights", List.of("i-1", "i-2"));
        trace.put("evidence.used.insights", List.of("i-2"));
        trace.put("retrieved.examples", List.of("e-1"));
        trace.put("used.examples", List.of("e-1"));
        trace.put("loaded.skills", List.of("s-1", "s-2"));
        trace.put("used.skills", List.of("s-2"));
        trace.put("evidence.retrieved.tasks", "t-1, t-2");
        trace.put("evidence.used.tasks", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("follow-up (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedBracketPathTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection[needs_memory]", true);
        trace.put("reflection[reason]", "flat bracket trace");
        trace.put("reflection[evidence_purposes][0]", "followup");
        trace.put("evidence[retrieved][insights][0]", "i-1");
        trace.put("evidence[retrieved][insights][1]", "i-2");
        trace.put("evidence[used][insights][0]", "i-2");
        trace.put("retrieved[examples][0]", "e-1");
        trace.put("used[examples][0]", "e-1");
        trace.put("loaded[skills][0]", "s-1");
        trace.put("loaded[skills][1]", "s-2");
        trace.put("used[skills][0]", "s-2");
        trace.put("retrieved[tasks][0]", "t-1");
        trace.put("retrieved[tasks][1]", "t-2");
        trace.put("used[tasks][0]", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedSlashPathTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection/needs_memory", true);
        trace.put("reflection/reason", "flat slash trace");
        trace.put("reflection/evidence_purposes/0", "followup");
        trace.put("evidence/retrieved/insights/0", "i-1");
        trace.put("evidence/retrieved/insights/1", "i-2");
        trace.put("evidence/used/insights/0", "i-2");
        trace.put("retrieved/examples/0", "e-1");
        trace.put("used/examples/0", "e-1");
        trace.put("loaded/skills/0", "s-1");
        trace.put("loaded/skills/1", "s-2");
        trace.put("used/skills/0", "s-2");
        trace.put("retrieved/tasks/0", "t-1");
        trace.put("retrieved/tasks/1", "t-2");
        trace.put("used/tasks/0", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedJsonPointerTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("/reflection/needs_memory", true);
        trace.put("/reflection/reason", "flat json pointer trace");
        trace.put("/reflection/evidence_purposes/0", "followup");
        trace.put("/evidence/retrieved/insights/0", "i-1");
        trace.put("/evidence/retrieved/insights/1", "i-2");
        trace.put("/evidence/used/insights/0", "i-2");
        trace.put("/retrieved/examples/0", "e-1");
        trace.put("/used/examples/0", "e-1");
        trace.put("/loaded/skills/0", "s-1");
        trace.put("/loaded/skills/1", "s-2");
        trace.put("/used/skills/0", "s-2");
        trace.put("/retrieved/tasks/0", "t-1");
        trace.put("/retrieved/tasks/1", "t-2");
        trace.put("/used/tasks/0", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedJsonPointerFragmentTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("#/reflection/needs_memory", true);
        trace.put("#/reflection/reason", "flat json pointer fragment trace");
        trace.put("#/reflection/evidence_purposes/0", "followup");
        trace.put("#/evidence/retrieved/insights/0", "i-1");
        trace.put("#/evidence/retrieved/insights/1", "i-2");
        trace.put("#/evidence/used/insights/0", "i-2");
        trace.put("#/retrieved/examples/0", "e-1");
        trace.put("#/used/examples/0", "e-1");
        trace.put("#/loaded/skills/0", "s-1");
        trace.put("#/loaded/skills/1", "s-2");
        trace.put("#/used/skills/0", "s-2");
        trace.put("#/retrieved/tasks/0", "t-1");
        trace.put("#/retrieved/tasks/1", "t-2");
        trace.put("#/used/tasks/0", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedJsonPathTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("$.reflection.needs_memory", true);
        trace.put("$['reflection'][\"reason\"]", "flat jsonpath trace");
        trace.put("$['reflection']['evidence_purposes'][0]", "followup");
        trace.put("$['evidence']['retrieved']['insights'][0]", "i-1");
        trace.put("$['evidence']['retrieved']['insights'][1]", "i-2");
        trace.put("$['evidence']['used']['insights'][0]", "i-2");
        trace.put("$.retrieved.examples[0]", "e-1");
        trace.put("$.used.examples[0]", "e-1");
        trace.put("$[loaded][skills][0]", "s-1");
        trace.put("$[loaded][skills][1]", "s-2");
        trace.put("$[used][skills][0]", "s-2");
        trace.put("$[retrieved][tasks][0]", "t-1");
        trace.put("$[retrieved][tasks][1]", "t-2");
        trace.put("$[used][tasks][0]", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedJsonPathFragmentTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("#$.reflection.needs_memory", true);
        trace.put("#$['reflection'][\"reason\"]", "flat jsonpath fragment trace");
        trace.put("#$['reflection']['evidence_purposes'][0]", "followup");
        trace.put("#$['evidence']['retrieved']['insights'][0]", "i-1");
        trace.put("#$['evidence']['retrieved']['insights'][1]", "i-2");
        trace.put("#$['evidence']['used']['insights'][0]", "i-2");
        trace.put("#$.retrieved.examples[0]", "e-1");
        trace.put("#$.used.examples[0]", "e-1");
        trace.put("#$[loaded][skills][0]", "s-1");
        trace.put("#$[loaded][skills][1]", "s-2");
        trace.put("#$[used][skills][0]", "s-2");
        trace.put("#$[retrieved][tasks][0]", "t-1");
        trace.put("#$[retrieved][tasks][1]", "t-2");
        trace.put("#$[used][tasks][0]", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedColonPathTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection:needs_memory", true);
        trace.put("reflection:reason", "flat colon path trace");
        trace.put("reflection:evidence_purposes:0", "followup");
        trace.put("evidence:retrieved:insights:0", "i-1");
        trace.put("evidence:retrieved:insights:1", "i-2");
        trace.put("evidence:used:insights:0", "i-2");
        trace.put("retrieved:examples:0", "e-1");
        trace.put("used:examples:0", "e-1");
        trace.put("loaded:skills:0", "s-1");
        trace.put("loaded:skills:1", "s-2");
        trace.put("used:skills:0", "s-2");
        trace.put("retrieved:tasks:0", "t-1");
        trace.put("retrieved:tasks:1", "t-2");
        trace.put("used:tasks:0", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedColonPathTraceFieldsWithDelimiterWhitespace() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection : needs_memory", true);
        trace.put("reflection : reason", "flat colon path trace with delimiter whitespace");
        trace.put("reflection : evidence_purposes : 0", "followup");
        trace.put("evidence : retrieved : insights : 0", "i-1");
        trace.put("evidence : retrieved : insights : 1", "i-2");
        trace.put("evidence : used : insights : 0", "i-2");
        trace.put("retrieved : examples : 0", "e-1");
        trace.put("used : examples : 0", "e-1");
        trace.put("loaded : skills : 0", "s-1");
        trace.put("loaded : skills : 1", "s-2");
        trace.put("used : skills : 0", "s-2");
        trace.put("retrieved : tasks : 0", "t-1");
        trace.put("retrieved : tasks : 1", "t-2");
        trace.put("used : tasks : 0", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedBackslashPathTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection\\needs_memory", true);
        trace.put("reflection\\reason", "flat backslash path trace");
        trace.put("reflection\\evidence_purposes\\0", "followup");
        trace.put("evidence\\retrieved\\insights\\0", "i-1");
        trace.put("evidence\\retrieved\\insights\\1", "i-2");
        trace.put("evidence\\used\\insights\\0", "i-2");
        trace.put("retrieved\\examples\\0", "e-1");
        trace.put("used\\examples\\0", "e-1");
        trace.put("loaded\\skills\\0", "s-1");
        trace.put("loaded\\skills\\1", "s-2");
        trace.put("used\\skills\\0", "s-2");
        trace.put("retrieved\\tasks\\0", "t-1");
        trace.put("retrieved\\tasks\\1", "t-2");
        trace.put("used\\tasks\\0", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedBackslashPathTraceFieldsWithDelimiterWhitespace() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("# \\ reflection \\ needs_memory", true);
        trace.put("# \\ reflection \\ reason", "flat backslash path trace with delimiter whitespace");
        trace.put("# \\ reflection \\ evidence_purposes \\ 0", "followup");
        trace.put("evidence \\ retrieved \\ insights \\ 0", "i-1");
        trace.put("evidence \\ retrieved \\ insights \\ 1", "i-2");
        trace.put("evidence \\ used \\ insights \\ 0", "i-2");
        trace.put("retrieved \\ examples \\ 0", "e-1");
        trace.put("used \\ examples \\ 0", "e-1");
        trace.put("loaded \\ skills \\ 0", "s-1");
        trace.put("loaded \\ skills \\ 1", "s-2");
        trace.put("used \\ skills \\ 0", "s-2");
        trace.put("retrieved \\ tasks \\ 0", "t-1");
        trace.put("retrieved \\ tasks \\ 1", "t-2");
        trace.put("used \\ tasks \\ 0", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedPathRootWithNamingDrift() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("MEMORY-LOADED", "YES");
        trace.put("#/REFLECTION-RESULT/NEEDS-MEMORY", "Y");
        trace.put("#/REFLECTION-RESULT/REASON", "flat path root naming drift trace");
        trace.put("#/REFLECTION-RESULT/EVIDENCE-PURPOSE/0", "follow-up");
        trace.put("#/RETRIEVED-INSIGHTS/0", "i-1");
        trace.put("#/RETRIEVED-INSIGHTS/1", "i-2");
        trace.put("#/USED-INSIGHTS/0", "i-2");
        trace.put("#/RETRIEVED-EXAMPLES/0", "e-1");
        trace.put("#/USED-EXAMPLES/0", "e-1");
        trace.put("#/RETRIEVED-SKILLS/0", "s-1");
        trace.put("#/RETRIEVED-SKILLS/1", "s-2");
        trace.put("#/USED-SKILLS/0", "s-2");
        trace.put("#/RETRIEVED-TASKS/0", "t-1");
        trace.put("#/RETRIEVED-TASKS/1", "t-2");
        trace.put("#/USED-TASKS/0", "t-2");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.memoryLoadedRate()).isEqualTo(1.0d);
        assertThat(report.needsMemoryRate()).isEqualTo(1.0d);
        assertThat(report.unknownNeedsMemoryRate()).isZero();
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("s-2 (1)");
        assertThat(report.topPurposes()).containsExactly("follow-up (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseFlattenedPathRootWithNamingDriftAndWhitespace() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("MEMORY-LOADED", "YES");
        trace.put("  #/REFLECTION-RESULT/NEEDS-MEMORY  ", "Y");
        trace.put("  #/RETRIEVED-INSIGHTS/0  ", "i-1");
        trace.put("  #/USED-INSIGHTS/0  ", "i-1");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.needsMemoryRate()).isEqualTo(1.0d);
        assertThat(report.insightStat().retrieved()).isEqualTo(1);
        assertThat(report.insightStat().used()).isEqualTo(1);
    }

    @Test
    void analyzeRecentTracesShouldParseKebabAndUppercaseTraceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("MEMORY-LOADED", "YES");
        trace.put("REFLECTION-RESULT", Map.of(
                "NEEDS-MEMORY", "Y",
                "REASON", "kebab uppercase trace",
                "EVIDENCE-PURPOSE", "follow-up"
        ));
        trace.put("RETRIEVED-INSIGHTS", "insight-a, insight-b");
        trace.put("USED-INSIGHTS", "insight-b");
        trace.put("RETRIEVED-EXAMPLES", "example-a");
        trace.put("USED-EXAMPLES", "example-a");
        trace.put("RETRIEVED-SKILLS", "debugging | planner");
        trace.put("USED-SKILLS", "planner");
        trace.put("RETRIEVED-TASKS", "task-a");
        trace.put("USED-TASKS", "task-a");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.memoryLoadedRate()).isEqualTo(1.0d);
        assertThat(report.needsMemoryRate()).isEqualTo(1.0d);
        assertThat(report.unknownNeedsMemoryRate()).isZero();
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(1);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topPurposes()).containsExactly("follow-up (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseMapStyleEvidenceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "map style evidence",
                "evidence_purposes", List.of("followup")
        ));
        trace.put("retrieved_insights", Map.of("insight-a", true, "insight-b", "yes", "insight-c", false));
        trace.put("used_insights", Map.of("insight-b", true));
        trace.put("retrieved_examples", Map.of(
                "case-1", Map.of("title", "example-a"),
                "case-2", Map.of("content", "example-b")
        ));
        trace.put("used_examples", Map.of("case-2", Map.of("content", "example-b")));
        trace.put("loaded_skills", Map.of("debugging", true, "planner", "yes", "refactor", "no"));
        trace.put("used_skills", Map.of("planner", true));
        trace.put("retrieved_tasks", Map.of("task-a", 1, "task-b", "on", "task-c", 0));
        trace.put("used_tasks", Map.of("task-b", true));
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(2);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(2);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("planner (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
    }

    @Test
    void analyzeRecentTracesShouldIgnoreNullLikeEvidenceTokens() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "过滤 null-like 噪声",
                "evidence_purposes", List.of("followup", "none", "N/A")
        ));
        trace.put("retrieved_insights", List.of("null", "insight-a", "undefined"));
        trace.put("used_insights", List.of("n/a", "insight-a"));
        trace.put("retrieved_examples", List.of("none", "example-a"));
        trace.put("used_examples", List.of("undefined", "example-a"));
        trace.put("loaded_skills", List.of("N/A", "debugging"));
        trace.put("used_skills", List.of("none", "debugging"));
        trace.put("retrieved_tasks", List.of("null", "task-a"));
        trace.put("used_tasks", List.of("undefined", "task-a"));
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(1);
        assertThat(report.insightStat().used()).isEqualTo(1);
        assertThat(report.exampleStat().retrieved()).isEqualTo(1);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(1);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(1);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topPurposes()).containsExactly("followup (1)");
        assertThat(report.topUsedSkills()).containsExactly("debugging (1)");
    }

    @Test
    void analyzeRecentTracesShouldParseDelimitedStringEvidenceFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryTraceInsightService service = new MemoryTraceInsightService(storage);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("memory_loaded", true);
        trace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "分隔字符串兼容",
                "evidence_purposes", List.of("followup")
        ));
        trace.put("retrieved_insights", "insight-a, insight-b; insight-c");
        trace.put("used_insights", "insight-a | insight-c");
        trace.put("retrieved_examples", "- example-a\n- example-b");
        trace.put("used_examples", "example-a");
        trace.put("loaded_skills", "debugging | planner");
        trace.put("used_skills", "planner");
        trace.put("retrieved_tasks", "1. task-a\n2. task-b");
        trace.put("used_tasks", "task-b");
        storage.appendMemoryEvidenceTrace(trace);

        MemoryTraceInsightService.InsightReport report = service.analyzeRecentTraces(20);

        assertThat(report.sampleSize()).isEqualTo(1);
        assertThat(report.insightStat().retrieved()).isEqualTo(3);
        assertThat(report.insightStat().used()).isEqualTo(2);
        assertThat(report.exampleStat().retrieved()).isEqualTo(2);
        assertThat(report.exampleStat().used()).isEqualTo(1);
        assertThat(report.skillStat().retrieved()).isEqualTo(2);
        assertThat(report.skillStat().used()).isEqualTo(1);
        assertThat(report.taskStat().retrieved()).isEqualTo(2);
        assertThat(report.taskStat().used()).isEqualTo(1);
        assertThat(report.topUsedSkills()).containsExactly("planner (1)");
        assertThat(report.topPurposes()).containsExactly("followup (1)");
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

    private String quoteJsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }
}
