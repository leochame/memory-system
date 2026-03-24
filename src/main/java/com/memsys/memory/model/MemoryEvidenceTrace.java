package com.memsys.memory.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Memory Evidence Trace — 记录单轮回答中的记忆反思与证据使用情况。
 * <p>
 * Phase 7 核心数据模型之一，用于回答后审计"系统用了什么记忆、为什么用"。
 * 配合 {@code /memory-debug} 命令可视化展示。
 *
 * @param timestamp       本轮处理时间
 * @param userMessage     用户输入（截断到 200 字符）
 * @param reflection      Memory Reflection 判断结果
 * @param memoryLoaded    是否实际加载了长期记忆
 * @param topOfMindCount  加载的 Top of Mind 记忆条数
 * @param ragResultCount  RAG 语义检索命中条数
 * @param examplesUsed    是否使用了 Example 案例
 * @param userInsightUsed 是否加载了用户画像正文
 * @param skillsAvailable 可用 Skill 数量
 * @param evidenceSummary 证据使用概要说明
 */
public record MemoryEvidenceTrace(
        LocalDateTime timestamp,
        String userMessage,
        ReflectionResult reflection,
        boolean memoryLoaded,
        int topOfMindCount,
        int ragResultCount,
        boolean examplesUsed,
        boolean userInsightUsed,
        int skillsAvailable,
        String evidenceSummary
) {

    /**
     * 构建证据概要文本，用于 /memory-debug 展示。
     */
    public String buildDisplaySummary() {
        StringBuilder sb = new StringBuilder();

        // 反思结论
        sb.append("== Memory Reflection ==\n");
        sb.append("需要记忆: ").append(reflection.needs_memory() ? "是" : "否").append("\n");
        sb.append("判断理由: ").append(reflection.reason()).append("\n");
        if (reflection.evidence_purposes() != null && !reflection.evidence_purposes().isEmpty()) {
            sb.append("证据用途: ").append(String.join(", ", reflection.evidence_purposes())).append("\n");
        }

        // 实际加载情况
        sb.append("\n== Evidence Loaded ==\n");
        sb.append("记忆加载: ").append(memoryLoaded ? "是" : "否（跳过）").append("\n");
        if (memoryLoaded) {
            sb.append("Top of Mind: ").append(topOfMindCount).append(" 条\n");
            sb.append("RAG 检索: ").append(ragResultCount).append(" 条\n");
            sb.append("用户画像: ").append(userInsightUsed ? "已加载" : "未加载").append("\n");
            sb.append("Example: ").append(examplesUsed ? "已使用" : "未使用").append("\n");
            sb.append("可用 Skill: ").append(skillsAvailable).append(" 个\n");
        }

        // 用户消息预览
        sb.append("\n== User Message ==\n");
        sb.append(userMessage).append("\n");

        return sb.toString();
    }
}
