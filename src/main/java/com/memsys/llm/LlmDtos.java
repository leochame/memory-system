package com.memsys.llm;

import java.util.List;

/**
 * LLM 结构化输出 DTO 集合。
 * 统一收敛到单文件，减少碎片化模型文件数量。
 */
public final class LlmDtos {

    private LlmDtos() {
    }

    public record ExampleItem(
            String problem,
            String solution,
            List<String> tags
    ) {
    }

    public record ExamplesResult(
            List<ExampleItem> items
    ) {
    }

    /**
     * 注意：字段名保持 snake_case，以便直接匹配 JSON Schema。
     */
    public record ExplicitMemoryResult(
            boolean has_memory,
            String slot_name,
            String content,
            String memory_type,
            String source
    ) {
    }

    public record SkillGenerationResult(
            boolean should_generate,
            String skill_name,
            String skill_content
    ) {
    }

    public record UserInsightItem(
            String slot_name,
            String content,
            String confidence
    ) {
    }

    public record UserInsightsResult(
            List<UserInsightItem> items
    ) {
    }

    public record ScheduledTaskResult(
            boolean has_task,
            String task_title,
            String task_detail,
            String due_at_iso
    ) {
    }

    /**
     * Memory Reflection 结构化输出：判断当前问题是否需要长期记忆。
     */
    public record MemoryReflectionResult(
            boolean needs_memory,
            String reason,
            java.util.List<String> evidence_purposes
    ) {
    }

    /**
     * 会话摘要结构化输出：由 LLM 对一段对话历史生成结构化摘要。
     * Phase 8 核心 DTO。
     */
    public record ConversationSummaryResult(
            String summary,
            List<String> key_topics,
            int turn_count,
            String time_range
    ) {
    }

    /**
     * 主题切换检测结构化输出：判断当前消息是否标志着对话主题的切换。
     * Phase 8 #2 — 长对话主题切换时生成 topic summary。
     */
    public record TopicShiftDetectionResult(
            boolean topic_shifted,
            String previous_topic,
            String current_topic
    ) {
    }

    /**
     * 主动提醒结构化输出：基于用户画像和记忆生成个性化的回顾与建议。
     * Phase 9 #5 — 基于记忆生成主动提醒、回顾和建议。
     */
    public record ProactiveReminderResult(
            boolean should_remind,
            String reminder_text,
            String reminder_type,
            List<String> based_on_memories,
            String suggested_action
    ) {
    }
}
