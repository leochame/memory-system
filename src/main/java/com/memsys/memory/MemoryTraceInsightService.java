package com.memsys.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memsys.memory.storage.MemoryStorage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String DELIMITED_LIST_SPLIT_REGEX = "[,;|\\n，；]+";

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

            List<String> retrievedInsightsList = readTraceList(
                    trace, "retrieved", "insights", "retrieved_insights", "retrievedInsights");
            List<String> usedInsightsList = readTraceList(
                    trace, "used", "insights", "used_insights", "usedInsights");
            List<String> retrievedExamplesList = readTraceList(
                    trace, "retrieved", "examples", "retrieved_examples", "retrievedExamples");
            List<String> usedExamplesList = readTraceList(
                    trace, "used", "examples", "used_examples", "usedExamples");
            List<String> loadedSkillsList = readTraceList(
                    trace,
                    "retrieved",
                    "skills",
                    "loaded_skills",
                    "loadedSkills",
                    "retrieved_skills",
                    "retrievedSkills");
            List<String> usedSkillsList = readTraceList(
                    trace, "used", "skills", "used_skills", "usedSkills");
            List<String> retrievedTasksList = readTraceList(
                    trace, "retrieved", "tasks", "retrieved_tasks", "retrievedTasks");
            List<String> usedTasksList = readTraceList(
                    trace, "used", "tasks", "used_tasks", "usedTasks");

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
            retrievedInsights += readTraceList(
                    trace, "retrieved", "insights", "retrieved_insights", "retrievedInsights").size();
            usedInsights += readTraceList(
                    trace, "used", "insights", "used_insights", "usedInsights").size();
            retrievedExamples += readTraceList(
                    trace, "retrieved", "examples", "retrieved_examples", "retrievedExamples").size();
            usedExamples += readTraceList(
                    trace, "used", "examples", "used_examples", "usedExamples").size();
            retrievedSkills += readTraceList(
                    trace,
                    "retrieved",
                    "skills",
                    "loaded_skills",
                    "loadedSkills",
                    "retrieved_skills",
                    "retrievedSkills").size();
            usedSkills += readTraceList(trace, "used", "skills", "used_skills", "usedSkills").size();
            retrievedTasks += readTraceList(
                    trace, "retrieved", "tasks", "retrieved_tasks", "retrievedTasks").size();
            usedTasks += readTraceList(
                    trace, "used", "tasks", "used_tasks", "usedTasks").size();
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
                String text = normalizeListItem(item);
                if (!text.isBlank()) {
                    items.add(text);
                }
            }
            return items;
        }
        if (value instanceof Map<?, ?> map) {
            return asStringListFromMap(map);
        }
        if (value instanceof String text) {
            String normalized = text.trim();
            if (normalized.isEmpty() || isNullLike(normalized)) {
                return List.of();
            }
            List<String> parsed = parseStringListFromJsonText(normalized);
            if (!parsed.isEmpty()) {
                return parsed;
            }
            List<String> parsedFromDelimitedText = parseStringListFromDelimitedText(normalized);
            if (!parsedFromDelimitedText.isEmpty()) {
                return parsedFromDelimitedText;
            }
            return List.of(normalized);
        }
        return List.of();
    }

    private List<String> asStringListFromMap(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = normalizeListItem(entry.getKey());
            Object rawValue = entry.getValue();
            Boolean boolValue = toNullableBoolean(rawValue);
            if (boolValue != null) {
                if (boolValue && !key.isBlank()) {
                    items.add(key);
                }
                continue;
            }
            String normalizedValue = normalizeListItem(rawValue);
            if (!normalizedValue.isBlank()) {
                items.add(normalizedValue);
                continue;
            }
            if (!key.isBlank()) {
                items.add(key);
            }
        }
        return items;
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
            Object normalizedMatch = readByNormalizedKey(source, key);
            if (normalizedMatch != null) {
                return normalizedMatch;
            }
            Map<String, Object> flattenedSubtree = readFlattenedSubtree(source, key);
            if (!flattenedSubtree.isEmpty()) {
                return flattenedSubtree;
            }
        }
        return null;
    }

    private Object readByNormalizedKey(Map<String, Object> source, String key) {
        if (source == null || source.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        String normalizedKey = normalizeLookupKey(key);
        if (normalizedKey.isBlank()) {
            return null;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String entryKey = entry.getKey();
            if (entryKey == null || entryKey.isBlank()) {
                continue;
            }
            if (normalizedKey.equals(normalizeLookupKey(entryKey))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String normalizeLookupKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFlattenedSubtree(Map<String, Object> source, String key) {
        if (source == null || source.isEmpty() || key == null || key.isBlank()) {
            return Map.of();
        }
        Map<String, Object> nested = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String entryKey = entry.getKey();
            if (entryKey == null) {
                continue;
            }
            String suffix = flattenedKeySuffix(entryKey, key);
            if (suffix == null || suffix.isBlank()) {
                continue;
            }
            List<String> parts = splitFlattenedPath(suffix);
            if (parts.isEmpty()) {
                continue;
            }
            Map<String, Object> cursor = nested;
            for (int i = 0; i < parts.size() - 1; i++) {
                String part = parts.get(i);
                if (part.isBlank()) {
                    continue;
                }
                Object existing = cursor.get(part);
                if (!(existing instanceof Map<?, ?> existingMap)) {
                    Map<String, Object> created = new LinkedHashMap<>();
                    cursor.put(part, created);
                    cursor = created;
                } else {
                    cursor = (Map<String, Object>) existingMap;
                }
            }
            String leaf = parts.get(parts.size() - 1);
            if (!leaf.isBlank()) {
                cursor.put(leaf, entry.getValue());
            }
        }
        return nested;
    }

    private String flattenedKeySuffix(String entryKey, String key) {
        if (entryKey == null || entryKey.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String normalizedKey = normalizeLookupKey(key);
        if (normalizedKey.isBlank()) {
            return null;
        }
        String candidate = entryKey.trim();
        if (candidate.startsWith("#")) {
            String fragment = candidate.substring(1).stripLeading();
            if (fragment.isBlank()) {
                return null;
            }
            candidate = trimLeadingFragmentDelimiter(fragment);
        } else if (candidate.startsWith("/") || candidate.startsWith("\\")) {
            candidate = candidate.substring(1);
        }
        if (candidate.startsWith("$")) {
            if (candidate.length() == 1) {
                return null;
            }
            char next = candidate.charAt(1);
            if (next != '.' && next != '/' && next != '[') {
                return null;
            }
            candidate = candidate.substring(1);
            if (candidate.startsWith(".") || candidate.startsWith("/")) {
                candidate = candidate.substring(1);
            }
        }
        if (candidate.isBlank()) {
            return null;
        }
        int delimiterIndex = -1;
        boolean doubleUnderscoreDelimiter = false;
        int doubleUnderscoreDelimiterLength = 0;
        boolean arrowDelimiter = false;
        int arrowDelimiterLength = 0;
        boolean fatArrowDelimiter = false;
        int fatArrowDelimiterLength = 0;
        boolean doubleAngleDelimiter = false;
        int doubleAngleDelimiterLength = 0;
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if (ch == '.' || ch == '[' || ch == '/' || ch == ':' || ch == '\\' || ch == '|' || ch == ';') {
                delimiterIndex = i;
                break;
            }
            int delimiterLength = matchDoubleUnderscoreDelimiter(candidate, i);
            if (delimiterLength > 0) {
                delimiterIndex = i;
                doubleUnderscoreDelimiter = true;
                doubleUnderscoreDelimiterLength = delimiterLength;
                break;
            }
            int arrowLength = matchArrowDelimiter(candidate, i);
            if (arrowLength > 0) {
                delimiterIndex = i;
                arrowDelimiter = true;
                arrowDelimiterLength = arrowLength;
                break;
            }
            int fatArrowLength = matchFatArrowDelimiter(candidate, i);
            if (fatArrowLength > 0) {
                delimiterIndex = i;
                fatArrowDelimiter = true;
                fatArrowDelimiterLength = fatArrowLength;
                break;
            }
            int doubleAngleLength = matchDoubleAngleDelimiter(candidate, i);
            if (doubleAngleLength > 0) {
                delimiterIndex = i;
                doubleAngleDelimiter = true;
                doubleAngleDelimiterLength = doubleAngleLength;
                break;
            }
        }
        if (delimiterIndex < 0) {
            return null;
        }
        if (delimiterIndex == 0 && candidate.charAt(0) == '[') {
            int closeIdx = candidate.indexOf(']');
            if (closeIdx <= 1) {
                return null;
            }
            String root = normalizeBracketToken(candidate.substring(1, closeIdx));
            if (!normalizedKey.equals(normalizeLookupKey(root))) {
                return null;
            }
            if (closeIdx + 1 >= candidate.length()) {
                return null;
            }
            return candidate.substring(closeIdx + 1);
        }
        if (delimiterIndex == 0) {
            return null;
        }
        String root = candidate.substring(0, delimiterIndex);
        if (!normalizedKey.equals(normalizeLookupKey(root))) {
            return null;
        }
        char delimiter = candidate.charAt(delimiterIndex);
        if (delimiter == '[') {
            return candidate.substring(delimiterIndex);
        }
        if (doubleUnderscoreDelimiter) {
            return candidate.substring(delimiterIndex + doubleUnderscoreDelimiterLength);
        }
        if (arrowDelimiter) {
            return candidate.substring(delimiterIndex + arrowDelimiterLength);
        }
        if (fatArrowDelimiter) {
            return candidate.substring(delimiterIndex + fatArrowDelimiterLength);
        }
        if (doubleAngleDelimiter) {
            return candidate.substring(delimiterIndex + doubleAngleDelimiterLength);
        }
        return candidate.substring(delimiterIndex + 1);
    }

    private String trimLeadingFragmentDelimiter(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return "";
        }
        int doubleUnderscoreDelimiterLength = matchDoubleUnderscoreDelimiter(fragment, 0);
        if (doubleUnderscoreDelimiterLength > 0) {
            return fragment.substring(doubleUnderscoreDelimiterLength).stripLeading();
        }
        int arrowDelimiterLength = matchArrowDelimiter(fragment, 0);
        if (arrowDelimiterLength > 0) {
            return fragment.substring(arrowDelimiterLength).stripLeading();
        }
        int fatArrowDelimiterLength = matchFatArrowDelimiter(fragment, 0);
        if (fatArrowDelimiterLength > 0) {
            return fragment.substring(fatArrowDelimiterLength).stripLeading();
        }
        int doubleAngleDelimiterLength = matchDoubleAngleDelimiter(fragment, 0);
        if (doubleAngleDelimiterLength > 0) {
            return fragment.substring(doubleAngleDelimiterLength).stripLeading();
        }
        char first = fragment.charAt(0);
        if (first == '/' || first == '\\' || first == '.' || first == ':' || first == '|' || first == ';') {
            return fragment.substring(1).stripLeading();
        }
        return fragment;
    }

    private List<String> splitFlattenedPath(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < suffix.length(); i++) {
            char ch = suffix.charAt(i);
            if (ch == '.' || ch == '/' || ch == ':' || ch == '\\' || ch == '|' || ch == ';') {
                if (!token.isEmpty()) {
                    addDecodedPathToken(parts, token);
                    token.setLength(0);
                }
                continue;
            }
            int delimiterLength = matchDoubleUnderscoreDelimiter(suffix, i);
            if (delimiterLength > 0) {
                if (!token.isEmpty()) {
                    addDecodedPathToken(parts, token);
                    token.setLength(0);
                }
                i += delimiterLength - 1;
                continue;
            }
            int arrowLength = matchArrowDelimiter(suffix, i);
            if (arrowLength > 0) {
                if (!token.isEmpty()) {
                    addDecodedPathToken(parts, token);
                    token.setLength(0);
                }
                i += arrowLength - 1;
                continue;
            }
            int fatArrowLength = matchFatArrowDelimiter(suffix, i);
            if (fatArrowLength > 0) {
                if (!token.isEmpty()) {
                    addDecodedPathToken(parts, token);
                    token.setLength(0);
                }
                i += fatArrowLength - 1;
                continue;
            }
            int doubleAngleLength = matchDoubleAngleDelimiter(suffix, i);
            if (doubleAngleLength > 0) {
                if (!token.isEmpty()) {
                    addDecodedPathToken(parts, token);
                    token.setLength(0);
                }
                i += doubleAngleLength - 1;
                continue;
            }
            if (ch == '[') {
                if (!token.isEmpty()) {
                    addDecodedPathToken(parts, token);
                    token.setLength(0);
                }
                int closeIdx = suffix.indexOf(']', i + 1);
                if (closeIdx > i + 1) {
                    String bracketToken = normalizeBracketToken(suffix.substring(i + 1, closeIdx));
                    if (!bracketToken.isBlank()) {
                        parts.add(bracketToken);
                    }
                    i = closeIdx;
                }
                continue;
            }
            token.append(ch);
        }
        if (!token.isEmpty()) {
            addDecodedPathToken(parts, token);
        }
        return parts;
    }

    private int matchDoubleUnderscoreDelimiter(String value, int start) {
        if (value == null || start < 0 || start >= value.length() || value.charAt(start) != '_') {
            return 0;
        }
        int index = start + 1;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        if (index >= value.length() || value.charAt(index) != '_') {
            return 0;
        }
        index++;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index - start;
    }

    private int matchArrowDelimiter(String value, int start) {
        if (value == null || start < 0 || start >= value.length() || value.charAt(start) != '-') {
            return 0;
        }
        int index = start + 1;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        if (index >= value.length() || value.charAt(index) != '>') {
            return 0;
        }
        index++;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index - start;
    }

    private int matchFatArrowDelimiter(String value, int start) {
        if (value == null || start < 0 || start >= value.length() || value.charAt(start) != '=') {
            return 0;
        }
        int index = start + 1;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        if (index >= value.length() || value.charAt(index) != '>') {
            return 0;
        }
        index++;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index - start;
    }

    private int matchDoubleAngleDelimiter(String value, int start) {
        if (value == null || start < 0 || start >= value.length() || value.charAt(start) != '>') {
            return 0;
        }
        int index = start + 1;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        if (index >= value.length() || value.charAt(index) != '>') {
            return 0;
        }
        index++;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index - start;
    }

    private void addDecodedPathToken(List<String> parts, StringBuilder token) {
        String decoded = decodeJsonPointerToken(token.toString()).trim();
        if (!decoded.isBlank()) {
            parts.add(decoded);
        }
    }

    private String decodeJsonPointerToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        return token.replace("~1", "/").replace("~0", "~");
    }

    private String normalizeBracketToken(String token) {
        String decoded = decodeJsonPointerToken(token);
        if (decoded.isBlank()) {
            return "";
        }
        String trimmed = decoded.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
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

    private List<String> readTraceList(Map<String, Object> trace,
                                       String group,
                                       String category,
                                       String... keys) {
        List<String> direct = asStringList(readFirstNonNull(trace, keys));
        if (!direct.isEmpty()) {
            return direct;
        }
        List<String> groupedFromTopLevel = readTraceListFromGroupedMap(trace, group, category, keys);
        if (!groupedFromTopLevel.isEmpty()) {
            return groupedFromTopLevel;
        }
        Map<String, Object> evidence = asMap(readFirstNonNull(trace, "evidence", "evidence_trace", "evidenceTrace"));
        if (evidence.isEmpty()) {
            return List.of();
        }
        List<String> directFromEvidence = asStringList(readFirstNonNull(evidence, keys));
        if (!directFromEvidence.isEmpty()) {
            return directFromEvidence;
        }
        return readTraceListFromGroupedMap(evidence, group, category, keys);
    }

    private List<String> readTraceListFromGroupedMap(Map<String, Object> source,
                                                     String group,
                                                     String category,
                                                     String... keys) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        String singular = singular(category);
        String[] groupedKeys = new String[4 + keys.length];
        groupedKeys[0] = category;
        groupedKeys[1] = singular;
        groupedKeys[2] = category + "_list";
        groupedKeys[3] = toCamel(category) + "List";
        System.arraycopy(keys, 0, groupedKeys, 4, keys.length);
        List<String> groupCandidates = new ArrayList<>();
        groupCandidates.add(group);
        groupCandidates.add(toCamel(group));
        if ("retrieved".equals(group)) {
            groupCandidates.add("loaded");
        }
        for (String candidateGroup : groupCandidates) {
            if (candidateGroup == null || candidateGroup.isBlank()) {
                continue;
            }
            Map<String, Object> grouped = asMap(readFirstNonNull(source, candidateGroup));
            if (grouped.isEmpty()) {
                continue;
            }
            List<String> values = asStringList(readFirstNonNull(grouped, groupedKeys));
            if (!values.isEmpty()) {
                return values;
            }
        }
        return List.of();
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
                String normalized = normalizeListItem(value);
                if (!normalized.isBlank()) {
                    items.add(normalized);
                }
            }
            return items;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> parseStringListFromDelimitedText(String rawText) {
        String text = normalizeText(rawText);
        if (text.isBlank()) {
            return List.of();
        }
        if (!containsListDelimiter(text)) {
            return List.of();
        }
        List<String> items = Arrays.stream(text.split(DELIMITED_LIST_SPLIT_REGEX))
                .map(this::normalizeDelimitedToken)
                .filter(s -> !s.isBlank())
                .toList();
        if (items.size() <= 1) {
            return List.of();
        }
        return items;
    }

    private boolean containsListDelimiter(String text) {
        return text.contains("\n")
                || text.contains(",")
                || text.contains("，")
                || text.contains(";")
                || text.contains("；")
                || text.contains("|");
    }

    private String normalizeDelimitedToken(String token) {
        if (token == null) {
            return "";
        }
        String stripped = token.replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "");
        return normalizeListItem(stripped);
    }

    private String unwrapJsonString(String rawText) {
        if (rawText == null) {
            return null;
        }
        String text = rawText.trim();
        if (text.isBlank()) {
            return text;
        }
        for (int depth = 0; depth < 6; depth++) {
            if ((text.startsWith("{") && text.endsWith("}"))
                    || (text.startsWith("[") && text.endsWith("]"))) {
                return text;
            }
            if (text.length() < 2 || !text.startsWith("\"") || !text.endsWith("\"")) {
                return text;
            }
            try {
                String unwrapped = TRACE_PARSER.readValue(text, String.class);
                if (unwrapped == null) {
                    return null;
                }
                String trimmed = unwrapped.trim();
                if (trimmed.equals(text)) {
                    return trimmed;
                }
                text = trimmed;
            } catch (Exception ignored) {
                return text;
            }
        }
        return text;
    }

    private String normalizeText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private String normalizeListItem(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String candidate = normalizeText(readFirstNonNull(
                    map,
                    "name",
                    "title",
                    "text",
                    "content",
                    "id",
                    "slot_name",
                    "slotName",
                    "value"
            ));
            if (!candidate.isBlank()) {
                return isNullLike(candidate) ? "" : candidate;
            }
        }
        String normalized = normalizeText(value);
        return isNullLike(normalized) ? "" : normalized;
    }

    private boolean isNullLike(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "null".equals(normalized)
                || "undefined".equals(normalized)
                || "n/a".equals(normalized)
                || "none".equals(normalized);
    }

    private String toCamel(String snakeOrWord) {
        if (snakeOrWord == null || snakeOrWord.isBlank()) {
            return "";
        }
        String[] parts = snakeOrWord.split("_");
        if (parts.length == 0) {
            return snakeOrWord;
        }
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private String singular(String plural) {
        if (plural == null || plural.isBlank()) {
            return "";
        }
        if (plural.endsWith("s") && plural.length() > 1) {
            return plural.substring(0, plural.length() - 1);
        }
        return plural;
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
