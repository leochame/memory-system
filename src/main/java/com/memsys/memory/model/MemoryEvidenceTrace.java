package com.memsys.memory.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

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
            sb.append("判断理由: ").append(reflection.reason()).append("\n");
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
        appendList(sb, "used_insights", usedInsights, 3);
        appendList(sb, "used_examples", usedExamples, 3);
        appendList(sb, "used_skills", usedSkills, 5);
        appendList(sb, "used_tasks", usedTasks, 3);
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
}
