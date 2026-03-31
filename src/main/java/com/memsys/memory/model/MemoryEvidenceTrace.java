package com.memsys.memory.model;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Memory Evidence Trace — 记录单轮回答中的记忆反思与证据使用情况。
 * <p>
 * Phase 7 核心数据模型之一，用于回答后审计"系统用了什么记忆、为什么用"。
 * 配合 {@code /memory-debug} 命令可视化展示。
 *
 * @param timestamp       本轮处理时间
 * @param userMessage     用户输入（截断到 200 字符）
 * @param reflection      Memory Reflection 判断结果
 * @param memoryLoaded        是否实际加载了长期记忆
 * @param retrievedInsights   被检索到的记忆证据（画像/TopOfMind/RAG）
 * @param usedInsights        判定参与本轮回答的记忆证据
 * @param retrievedExamples   被检索到的案例证据
 * @param usedExamples        判定参与本轮回答的案例证据
 * @param loadedSkills        可用 skill 列表
 * @param usedSkills          本轮真实调用的 skill 列表（load_skill）
 * @param retrievedTasks      被检索到的任务证据
 * @param usedTasks           判定参与本轮回答的任务证据
 * @param usedEvidenceSummary 最终证据摘要说明
 */
public record MemoryEvidenceTrace(
        LocalDateTime timestamp,
        String userMessage,
        ReflectionResult reflection,
        boolean memoryLoaded,
        List<String> retrievedInsights,
        List<String> usedInsights,
        List<String> retrievedExamples,
        List<String> usedExamples,
        List<String> loadedSkills,
        List<String> usedSkills,
        List<String> retrievedTasks,
        List<String> usedTasks,
        String usedEvidenceSummary
) {

    /**
     * 构建证据概要文本，用于 /memory-debug 展示。
     */
    public String buildDisplaySummary() {
        StringBuilder sb = new StringBuilder();

        // 反思结论
        sb.append("== Memory Reflection ==\n");
        if (reflection == null) {
            sb.append("需要记忆: unknown\n");
            sb.append("判断理由: reflection_missing\n");
        } else {
            sb.append("需要记忆: ").append(reflection.needs_memory() ? "是" : "否").append("\n");
            if (reflection.memory_purpose() != null && !reflection.memory_purpose().isBlank()) {
                sb.append("记忆目的: ").append(reflection.memory_purpose().trim()).append("\n");
            }
            sb.append("判断理由: ").append(reflection.reason()).append("\n");
            sb.append("判断置信度: ").append(String.format(Locale.ROOT, "%.2f", reflection.confidence())).append("\n");
            if (reflection.retrieval_hint() != null && !reflection.retrieval_hint().isBlank()) {
                sb.append("检索提示: ").append(reflection.retrieval_hint().trim()).append("\n");
            }
            if (reflection.evidence_types() != null && !reflection.evidence_types().isEmpty()) {
                sb.append("证据类型: ").append(String.join(", ", reflection.evidence_types())).append("\n");
            }
            if (reflection.evidence_purposes() != null && !reflection.evidence_purposes().isEmpty()) {
                sb.append("证据用途: ").append(String.join(", ", reflection.evidence_purposes())).append("\n");
            }
        }

        // 实际加载情况
        sb.append("\n== Evidence Retrieved ==\n");
        sb.append("记忆加载: ").append(memoryLoaded ? "是" : "否（跳过）").append("\n");
        if (memoryLoaded) {
            sb.append("Insights: ").append(sizeOf(retrievedInsights)).append(" 条\n");
            sb.append("Examples: ").append(sizeOf(retrievedExamples)).append(" 条\n");
            sb.append("Skills: ").append(sizeOf(loadedSkills)).append(" 个\n");
            sb.append("Tasks: ").append(sizeOf(retrievedTasks)).append(" 条\n");
            appendList(sb, "retrieved_insights", retrievedInsights, 3);
            appendList(sb, "retrieved_examples", retrievedExamples, 3);
            appendList(sb, "loaded_skills", loadedSkills, 5);
            appendList(sb, "retrieved_tasks", retrievedTasks, 3);
        }

        sb.append("\n== Evidence Used ==\n");
        sb.append("Insights: ").append(sizeOf(usedInsights)).append(" 条\n");
        sb.append("Examples: ").append(sizeOf(usedExamples)).append(" 条\n");
        sb.append("Skills: ").append(sizeOf(usedSkills)).append(" 个\n");
        sb.append("Tasks: ").append(sizeOf(usedTasks)).append(" 条\n");
        sb.append(String.format("覆盖率: Insights %s | Examples %s | Skills %s | Tasks %s\n",
                coverage(sizeOf(usedInsights), sizeOf(retrievedInsights)),
                coverage(sizeOf(usedExamples), sizeOf(retrievedExamples)),
                coverage(sizeOf(usedSkills), sizeOf(loadedSkills)),
                coverage(sizeOf(usedTasks), sizeOf(retrievedTasks))));
        appendCoverageDiagnostics(sb);
        appendList(sb, "used_insights", usedInsights, 3);
        appendList(sb, "used_examples", usedExamples, 3);
        appendList(sb, "used_skills", usedSkills, 5);
        appendList(sb, "used_tasks", usedTasks, 3);
        appendUnusedList(sb, "unused_insights", retrievedInsights, usedInsights, 3);
        appendUnusedList(sb, "unused_examples", retrievedExamples, usedExamples, 3);
        appendUnusedList(sb, "unused_skills", loadedSkills, usedSkills, 3);
        appendUnusedList(sb, "unused_tasks", retrievedTasks, usedTasks, 3);
        if (usedEvidenceSummary != null && !usedEvidenceSummary.isBlank()) {
            sb.append("摘要: ").append(usedEvidenceSummary.trim()).append("\n");
        }

        // 用户消息预览
        sb.append("\n== User Message ==\n");
        sb.append(userMessage).append("\n");

        return sb.toString();
    }

    private int sizeOf(List<String> items) {
        return items == null ? 0 : items.size();
    }

    private String coverage(int used, int retrieved) {
        if (retrieved <= 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f%%", (used * 100.0d) / retrieved);
    }

    private void appendCoverageDiagnostics(StringBuilder sb) {
        appendCoverageDiagnostic(sb, "Insights", sizeOf(usedInsights), sizeOf(retrievedInsights));
        appendCoverageDiagnostic(sb, "Examples", sizeOf(usedExamples), sizeOf(retrievedExamples));
        appendCoverageDiagnostic(sb, "Skills", sizeOf(usedSkills), sizeOf(loadedSkills));
        appendCoverageDiagnostic(sb, "Tasks", sizeOf(usedTasks), sizeOf(retrievedTasks));
    }

    private void appendCoverageDiagnostic(StringBuilder sb, String label, int used, int retrieved) {
        if (retrieved <= 0) {
            return;
        }
        if (used <= 0) {
            sb.append("诊断: ").append(label).append(" 已检索但未使用（0/").append(retrieved).append("）\n");
            return;
        }
        double ratio = used * 1.0d / retrieved;
        if (ratio < 0.5d) {
            sb.append("诊断: ").append(label).append(" 使用偏低（")
                    .append(used).append("/").append(retrieved).append("）\n");
        }
    }

    private void appendList(StringBuilder sb, String label, List<String> items, int maxItems) {
        if (items == null || items.isEmpty() || maxItems <= 0) {
            return;
        }
        sb.append("- ").append(label).append(":\n");
        int limit = Math.min(items.size(), maxItems);
        for (int i = 0; i < limit; i++) {
            sb.append("  ").append(i + 1).append(") ").append(items.get(i)).append("\n");
        }
        if (items.size() > limit) {
            sb.append("  ... +").append(items.size() - limit).append(" 条\n");
        }
    }

    private void appendUnusedList(StringBuilder sb,
                                  String label,
                                  List<String> retrieved,
                                  List<String> used,
                                  int maxItems) {
        if (retrieved == null || retrieved.isEmpty() || maxItems <= 0) {
            return;
        }
        Set<String> usedKeys = normalizeForDiff(used).keySet();
        List<String> unused = normalizeForDiff(retrieved).entrySet().stream()
                .filter(entry -> !usedKeys.contains(entry.getKey()))
                .map(java.util.Map.Entry::getValue)
                .toList();
        if (unused.isEmpty()) {
            return;
        }
        appendList(sb, label, unused, maxItems);
    }

    private LinkedHashMap<String, String> normalizeForDiff(List<String> items) {
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return normalized;
        }
        items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(item -> normalized.putIfAbsent(canonicalEvidenceKey(item), item));
        return normalized;
    }

    private String canonicalEvidenceKey(String item) {
        if (item == null) {
            return "";
        }
        return item.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
