package com.memsys.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memsys.memory.storage.MemoryStorage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 从 memory_evidence_traces.jsonl 生成可执行的系统优化洞察。
 */
@Service
public class MemoryTraceInsightService {

    private static final int DEFAULT_LIMIT = 50;
    private static final ObjectMapper TRACE_PARSER = new ObjectMapper();

    private final MemoryStorage storage;

    public MemoryTraceInsightService(MemoryStorage storage) {
        this.storage = storage;
    }

    public InsightReport analyzeRecentTraces(int limit) {
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        List<Map<String, Object>> traces = storage.readMemoryEvidenceTraces(effectiveLimit);
        return analyze(traces, effectiveLimit);
    }

    InsightReport analyze(List<Map<String, Object>> traces, int requestedLimit) {
        List<Map<String, Object>> safeTraces = traces == null ? List.of() : traces;
        int sampleSize = safeTraces.size();
        if (sampleSize == 0) {
            return new InsightReport(
                    requestedLimit,
                    0,
                    0d,
                    0d,
                    0d,
                    stat(0, 0),
                    stat(0, 0),
                    stat(0, 0),
                    stat(0, 0),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("暂无证据历史，请先进行多轮对话后再执行 /memory-insights。"),
                    TrendSummary.unavailable()
            );
        }

        int memoryLoadedCount = 0;
        int needsMemoryCount = 0;
        int unknownNeedsMemoryCount = 0;

        int retrievedInsights = 0;
        int usedInsights = 0;
        int retrievedExamples = 0;
        int usedExamples = 0;
        int loadedSkills = 0;
        int usedSkills = 0;
        int retrievedTasks = 0;
        int usedTasks = 0;

        Map<String, Integer> skillFreq = new LinkedHashMap<>();
        Map<String, Integer> purposeFreq = new LinkedHashMap<>();
        Map<String, Integer> reasonFreq = new LinkedHashMap<>();
        Map<String, PurposeAccumulator> purposeStats = new LinkedHashMap<>();

        for (Map<String, Object> trace : safeTraces) {
            boolean memoryLoaded = toBoolean(readFirstNonNull(trace, "memory_loaded", "memoryLoaded"));
            if (memoryLoaded) {
                memoryLoadedCount++;
            }

            Map<String, Object> reflection = parseReflection(trace);
            Boolean needsMemory = toNullableBoolean(readFirstNonNull(reflection, "needs_memory", "needsMemory"));
            if (needsMemory == null) {
                unknownNeedsMemoryCount++;
            } else if (needsMemory) {
                needsMemoryCount++;
            }
            String reason = normalizeText(readFirstNonNull(reflection, "reason"));
            if (!reason.isBlank()) {
                increment(reasonFreq, reason);
            }
            List<String> normalizedPurposes = normalizePurposes(readPurposes(reflection));
            for (String purpose : normalizedPurposes) {
                increment(purposeFreq, purpose);
            }

            List<String> retrievedInsightsList = readTraceList(trace, "retrieved_insights", "retrievedInsights");
            List<String> usedInsightsList = readTraceList(trace, "used_insights", "usedInsights");
            List<String> retrievedExamplesList = readTraceList(trace, "retrieved_examples", "retrievedExamples");
            List<String> usedExamplesList = readTraceList(trace, "used_examples", "usedExamples");
            List<String> loadedSkillsList = readTraceList(
                    trace, "loaded_skills", "loadedSkills", "retrieved_skills", "retrievedSkills");
            List<String> usedSkillsList = readTraceList(trace, "used_skills", "usedSkills");
            List<String> retrievedTasksList = readTraceList(trace, "retrieved_tasks", "retrievedTasks");
            List<String> usedTasksList = readTraceList(trace, "used_tasks", "usedTasks");

            retrievedInsights += retrievedInsightsList.size();
            usedInsights += usedInsightsList.size();
            retrievedExamples += retrievedExamplesList.size();
            usedExamples += usedExamplesList.size();
            loadedSkills += loadedSkillsList.size();
            usedSkills += usedSkillsList.size();
            retrievedTasks += retrievedTasksList.size();
            usedTasks += usedTasksList.size();

            for (String skill : usedSkillsList) {
                increment(skillFreq, skill);
            }

            for (String purpose : normalizedPurposes) {
                PurposeAccumulator acc = purposeStats.computeIfAbsent(purpose, ignored -> new PurposeAccumulator());
                acc.samples++;
                if (memoryLoaded) {
                    acc.memoryLoadedCount++;
                }
                acc.retrievedInsights += retrievedInsightsList.size();
                acc.usedInsights += usedInsightsList.size();
                acc.retrievedExamples += retrievedExamplesList.size();
                acc.usedExamples += usedExamplesList.size();
                acc.retrievedSkills += loadedSkillsList.size();
                acc.usedSkills += usedSkillsList.size();
                acc.retrievedTasks += retrievedTasksList.size();
                acc.usedTasks += usedTasksList.size();
            }
        }

        List<String> topSkills = topN(skillFreq, 5);
        List<String> topPurposes = topN(purposeFreq, 5);
        List<String> topReasons = topN(reasonFreq, 5);

        EvidenceStat insightStat = stat(retrievedInsights, usedInsights);
        EvidenceStat exampleStat = stat(retrievedExamples, usedExamples);
        EvidenceStat skillStat = stat(loadedSkills, usedSkills);
        EvidenceStat taskStat = stat(retrievedTasks, usedTasks);
        List<PurposeInsight> purposeInsights = buildPurposeInsights(purposeStats, 5);
        List<String> suggestions = buildSuggestions(
                sampleSize,
                memoryLoadedCount,
                needsMemoryCount,
                unknownNeedsMemoryCount,
                insightStat,
                exampleStat,
                skillStat,
                taskStat
        );
        TrendSummary trendSummary = buildTrendSummary(safeTraces);

        return new InsightReport(
                requestedLimit,
                sampleSize,
                rate(memoryLoadedCount, sampleSize),
                rate(needsMemoryCount, sampleSize),
                rate(unknownNeedsMemoryCount, sampleSize),
                insightStat,
                exampleStat,
                skillStat,
                taskStat,
                topSkills,
                topPurposes,
                topReasons,
                purposeInsights,
                suggestions,
                trendSummary
        );
    }

    private List<PurposeInsight> buildPurposeInsights(Map<String, PurposeAccumulator> purposeStats, int topN) {
        if (purposeStats == null || purposeStats.isEmpty() || topN <= 0) {
            return List.of();
        }
        return purposeStats.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().samples, a.getValue().samples))
                .limit(topN)
                .map(e -> {
                    PurposeAccumulator s = e.getValue();
                    return new PurposeInsight(
                            e.getKey(),
                            s.samples,
                            rate(s.memoryLoadedCount, s.samples),
                            rate(s.usedInsights, s.retrievedInsights),
                            rate(s.usedExamples, s.retrievedExamples),
                            rate(s.usedSkills, s.retrievedSkills),
                            rate(s.usedTasks, s.retrievedTasks)
                    );
                })
                .toList();
    }

    private List<String> buildSuggestions(int sampleSize,
                                          int memoryLoadedCount,
                                          int needsMemoryCount,
                                          int unknownNeedsMemoryCount,
                                          EvidenceStat insightStat,
                                          EvidenceStat exampleStat,
                                          EvidenceStat skillStat,
                                          EvidenceStat taskStat) {
        List<String> suggestions = new ArrayList<>();

        if (sampleSize < 10) {
            suggestions.add("样本量较小（<10），建议先积累更多对话再据此调参。");
        }
        if (needsMemoryCount > 0 && memoryLoadedCount == 0) {
            suggestions.add("反思判断需要记忆，但实际加载率为 0%；请检查全局开关和调用链。");
        }
        if (rate(unknownNeedsMemoryCount, sampleSize) >= 0.20d) {
            suggestions.add("反思字段 needs_memory 缺失/非法占比较高（>=20%），建议检查结构化输出与trace落盘规范。");
        }
        if (insightStat.retrieved() >= 20 && insightStat.usageRate() < 0.25d) {
            suggestions.add("insight 检索较多但使用率偏低，建议收紧检索范围或提高反思匹配门槛。");
        }
        if (exampleStat.retrieved() >= 10 && exampleStat.usageRate() < 0.20d) {
            suggestions.add("example 使用率偏低，建议优化案例标签或提高 problem/solution 结构质量。");
        }
        if (skillStat.retrieved() >= 10 && skillStat.usageRate() < 0.20d) {
            suggestions.add("skill 可用但调用较少，建议在系统提示中增强 skill 使用触发条件。");
        }
        if (taskStat.retrieved() >= 10 && taskStat.usageRate() < 0.20d) {
            suggestions.add("任务证据命中后使用偏低，建议增强 followup 场景下的任务引用策略。");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("当前证据使用结构健康，可继续扩大样本并按场景细化优化。");
        }
        return suggestions;
    }

    private TrendSummary buildTrendSummary(List<Map<String, Object>> traces) {
        int sampleSize = traces.size();
        if (sampleSize < 6) {
            return TrendSummary.unavailable();
        }
        int split = sampleSize / 2;
        List<Map<String, Object>> previousWindow = traces.subList(0, split);
        List<Map<String, Object>> recentWindow = traces.subList(split, sampleSize);
        if (previousWindow.isEmpty() || recentWindow.isEmpty()) {
            return TrendSummary.unavailable();
        }

        WindowMetrics prev = computeWindowMetrics(previousWindow);
        WindowMetrics recent = computeWindowMetrics(recentWindow);

        return new TrendSummary(
                previousWindow.size(),
                recentWindow.size(),
                trend(prev.memoryLoadedRate(), recent.memoryLoadedRate()),
                trend(prev.insightUsageRate(), recent.insightUsageRate()),
                trend(prev.exampleUsageRate(), recent.exampleUsageRate()),
                trend(prev.skillUsageRate(), recent.skillUsageRate()),
                trend(prev.taskUsageRate(), recent.taskUsageRate())
        );
    }

    private WindowMetrics computeWindowMetrics(List<Map<String, Object>> traces) {
        int memoryLoaded = 0;
        int retrievedInsights = 0;
        int usedInsights = 0;
        int retrievedExamples = 0;
        int usedExamples = 0;
        int retrievedSkills = 0;
        int usedSkills = 0;
        int retrievedTasks = 0;
        int usedTasks = 0;

        for (Map<String, Object> trace : traces) {
            if (toBoolean(readFirstNonNull(trace, "memory_loaded", "memoryLoaded"))) {
                memoryLoaded++;
            }
            retrievedInsights += readTraceList(trace, "retrieved_insights", "retrievedInsights").size();
            usedInsights += readTraceList(trace, "used_insights", "usedInsights").size();
            retrievedExamples += readTraceList(trace, "retrieved_examples", "retrievedExamples").size();
            usedExamples += readTraceList(trace, "used_examples", "usedExamples").size();
            retrievedSkills += readTraceList(
                    trace, "loaded_skills", "loadedSkills", "retrieved_skills", "retrievedSkills").size();
            usedSkills += readTraceList(trace, "used_skills", "usedSkills").size();
            retrievedTasks += readTraceList(trace, "retrieved_tasks", "retrievedTasks").size();
            usedTasks += readTraceList(trace, "used_tasks", "usedTasks").size();
        }

        return new WindowMetrics(
                rate(memoryLoaded, traces.size()),
                rate(usedInsights, retrievedInsights),
                rate(usedExamples, retrievedExamples),
                rate(usedSkills, retrievedSkills),
                rate(usedTasks, retrievedTasks)
        );
    }

    private TrendStat trend(double previousRate, double recentRate) {
        return new TrendStat(previousRate, recentRate, recentRate - previousRate);
    }

    private EvidenceStat stat(int retrieved, int used) {
        return new EvidenceStat(retrieved, used, rate(used, retrieved));
    }

    private double rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return (double) numerator / (double) denominator;
    }

    private void increment(Map<String, Integer> freq, String key) {
        String normalized = key == null ? "" : key.trim();
        if (normalized.isBlank()) {
            return;
        }
        freq.put(normalized, freq.getOrDefault(normalized, 0) + 1);
    }

    private List<String> topN(Map<String, Integer> freq, int n) {
        if (freq.isEmpty() || n <= 0) {
            return List.of();
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(n)
                .map(e -> e.getKey() + " (" + e.getValue() + ")")
                .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        if (value instanceof String rawText) {
            String text = unwrapJsonString(rawText);
            if (text != null && text.startsWith("{") && text.endsWith("}")) {
                try {
                    return TRACE_PARSER.readValue(text, new TypeReference<Map<String, Object>>() {
                    });
                } catch (Exception ignored) {
                    return Map.of();
                }
            }
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object value) {
        if (value instanceof Collection<?> collection) {
            List<String> items = new ArrayList<>();
            for (Object item : collection) {
                String text = normalizeText(item);
                if (!text.isBlank()) {
                    items.add(text);
                }
            }
            return items;
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if (normalized.isEmpty()) {
                return List.of();
            }
            List<String> parsed = parseStringListFromJsonText(normalized);
            if (!parsed.isEmpty()) {
                return parsed;
            }
            return List.of(normalized);
        }
        return List.of();
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = normalizeText(value).toLowerCase(Locale.ROOT);
        return "true".equals(text)
                || "1".equals(text)
                || "yes".equals(text)
                || "y".equals(text)
                || "on".equals(text)
                || "是".equals(text);
    }

    private Boolean toNullableBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        String text = normalizeText(value).toLowerCase(Locale.ROOT);
        if ("true".equals(text)
                || "1".equals(text)
                || "yes".equals(text)
                || "y".equals(text)
                || "on".equals(text)
                || "是".equals(text)) {
            return true;
        }
        if ("false".equals(text)
                || "0".equals(text)
                || "no".equals(text)
                || "n".equals(text)
                || "off".equals(text)
                || "否".equals(text)) {
            return false;
        }
        return null;
    }

    private Object readFirstNonNull(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = source.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Map<String, Object> parseReflection(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return Map.of();
        }
        Object reflectionObj = readFirstNonNull(trace, "reflection", "reflection_result", "reflectionResult");
        Map<String, Object> reflection = asMap(reflectionObj);
        if (!reflection.isEmpty()) {
            return reflection;
        }
        return trace;
    }

    private List<String> readPurposes(Map<String, Object> reflection) {
        List<String> purposes = asStringList(readFirstNonNull(
                reflection, "evidence_purposes", "evidencePurposes"));
        if (!purposes.isEmpty()) {
            return purposes;
        }
        return asStringList(readFirstNonNull(
                reflection, "evidence_purpose", "evidencePurpose"));
    }

    private List<String> readTraceList(Map<String, Object> trace, String... keys) {
        return asStringList(readFirstNonNull(trace, keys));
    }

    private List<String> normalizePurposes(List<String> purposes) {
        if (purposes == null || purposes.isEmpty()) {
            return List.of();
        }
        return purposes.stream()
                .map(this::normalizeText)
                .map(p -> p.toLowerCase(Locale.ROOT))
                .filter(p -> !p.isBlank())
                .distinct()
                .toList();
    }

    private List<String> parseStringListFromJsonText(String rawText) {
        String text = unwrapJsonString(rawText);
        if (text == null || text.isBlank() || !text.startsWith("[") || !text.endsWith("]")) {
            return List.of();
        }
        try {
            List<Object> values = TRACE_PARSER.readValue(text, new TypeReference<List<Object>>() {
            });
            List<String> items = new ArrayList<>();
            for (Object value : values) {
                String normalized = normalizeText(value);
                if (!normalized.isBlank()) {
                    items.add(normalized);
                }
            }
            return items;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String unwrapJsonString(String rawText) {
        if (rawText == null) {
            return null;
        }
        String text = rawText.trim();
        if (text.isBlank()) {
            return text;
        }
        if ((text.startsWith("{") && text.endsWith("}"))
                || (text.startsWith("[") && text.endsWith("]"))) {
            return text;
        }
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            try {
                String unwrapped = TRACE_PARSER.readValue(text, String.class);
                return unwrapped == null ? null : unwrapped.trim();
            } catch (Exception ignored) {
                return text;
            }
        }
        return text;
    }

    private String normalizeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record EvidenceStat(int retrieved, int used, double usageRate) {
    }

    private static class PurposeAccumulator {
        int samples = 0;
        int memoryLoadedCount = 0;
        int retrievedInsights = 0;
        int usedInsights = 0;
        int retrievedExamples = 0;
        int usedExamples = 0;
        int retrievedSkills = 0;
        int usedSkills = 0;
        int retrievedTasks = 0;
        int usedTasks = 0;
    }

    public record PurposeInsight(
            String purpose,
            int sampleSize,
            double memoryLoadedRate,
            double insightUsageRate,
            double exampleUsageRate,
            double skillUsageRate,
            double taskUsageRate
    ) {
    }

    private record WindowMetrics(
            double memoryLoadedRate,
            double insightUsageRate,
            double exampleUsageRate,
            double skillUsageRate,
            double taskUsageRate
    ) {
    }

    public record TrendStat(double previousRate, double recentRate, double delta) {
    }

    public record TrendSummary(
            int previousWindowSize,
            int recentWindowSize,
            TrendStat memoryLoadedTrend,
            TrendStat insightUsageTrend,
            TrendStat exampleUsageTrend,
            TrendStat skillUsageTrend,
            TrendStat taskUsageTrend
    ) {
        static TrendSummary unavailable() {
            return new TrendSummary(
                    0,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }

        public boolean available() {
            return previousWindowSize > 0
                    && recentWindowSize > 0
                    && memoryLoadedTrend != null
                    && insightUsageTrend != null
                    && exampleUsageTrend != null
                    && skillUsageTrend != null
                    && taskUsageTrend != null;
        }
    }

    public record InsightReport(
            int requestedLimit,
            int sampleSize,
            double memoryLoadedRate,
            double needsMemoryRate,
            double unknownNeedsMemoryRate,
            EvidenceStat insightStat,
            EvidenceStat exampleStat,
            EvidenceStat skillStat,
            EvidenceStat taskStat,
            List<String> topUsedSkills,
            List<String> topPurposes,
            List<String> topReasons,
            List<PurposeInsight> topPurposeInsights,
            List<String> suggestions,
            TrendSummary trendSummary
    ) {
    }
}
