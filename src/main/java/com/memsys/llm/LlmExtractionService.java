package com.memsys.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.memsys.llm.LlmDtos.ExampleItem;
import com.memsys.llm.LlmDtos.ExamplesResult;
import com.memsys.llm.LlmDtos.ExplicitMemoryResult;
import com.memsys.llm.LlmDtos.MemoryReflectionResult;
import com.memsys.llm.LlmDtos.ScheduledTaskResult;
import com.memsys.llm.LlmDtos.SkillGenerationResult;
import com.memsys.llm.LlmDtos.UserInsightItem;
import com.memsys.llm.LlmDtos.UserInsightsResult;
import com.memsys.llm.schema.Schemas;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class LlmExtractionService {

    private final LlmClient llmClient;

    public LlmExtractionService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    // ========== Skill 生成 ==========

    public Optional<SkillGenerationResult> generateSkill(List<Map<String, Object>> conversationHistory) {
        try {
            String historyText = conversationHistory.stream()
                    .limit(80)
                    .map(entry -> String.format("%s: %s", entry.get("role"), entry.get("message")))
                    .collect(Collectors.joining("\n"));

            String instruction = """
                反思以下对话，判断其中是否包含可复用的方法论、工作流程或技能。
                - 如果发现了可复用的方法论，should_generate=true，并给出 skill_name 和 skill_content（Markdown 格式）。
                - skill_name 使用 snake_case，如 code_review_checklist。
                - skill_content 应该是一个完整的、可独立使用的指南。
                - 如果对话中没有可复用的方法论，should_generate=false，其他字段填空字符串。

                对话历史：
                %s
                """.formatted(historyText);

            SkillGenerationResult result = llmClient.chatWithJsonSchema(
                    null,
                    List.of(new UserMessage(instruction)),
                    Schemas.skillGenerationResult(),
                    SkillGenerationResult.class
            );

            if (result != null && result.should_generate()
                    && result.skill_name() != null && !result.skill_name().isBlank()
                    && result.skill_content() != null && !result.skill_content().isBlank()) {
                return Optional.of(result);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Skill generation failed", e);
            return Optional.empty();
        }
    }

    // ========== Example 提取 ==========

    public List<ExampleItem> extractExamples(List<Map<String, Object>> conversationHistory) {
        try {
            String historyText = conversationHistory.stream()
                    .limit(80)
                    .map(entry -> String.format("%s: %s", entry.get("role"), entry.get("message")))
                    .collect(Collectors.joining("\n"));

            String instruction = """
                从以下对话中识别 problem/solution 对。
                - 每个 problem 是用户提出的问题或遇到的困难。
                - 每个 solution 是助手给出的解决方案或方法。
                - tags 是分类标签（如 "java", "debugging", "architecture"）。
                - items 允许为空数组。

                对话历史：
                %s
                """.formatted(historyText);

            ExamplesResult parsed = llmClient.chatWithJsonSchema(
                    null,
                    List.of(new UserMessage(instruction)),
                    Schemas.examplesResult(),
                    ExamplesResult.class
            );

            if (parsed == null || parsed.items() == null) {
                return List.of();
            }
            return parsed.items().stream()
                    .filter(item -> item.problem() != null && !item.problem().isBlank()
                            && item.solution() != null && !item.solution().isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("Example extraction failed", e);
            return List.of();
        }
    }

    // ========== 显式记忆提取 ==========

    public Map<String, Object> extractExplicitMemory(String userMessage) {
        try {
            String instruction = """
                分析以下用户消息，判断是否包含需要记住的显式信息（如用户偏好、个人信息等）。
                - 如果不包含，has_memory=false，其他字段填空字符串或任意合法枚举值。
                - 如果包含，has_memory=true，并给出 slot_name/content/memory_type/source。

                用户消息：
                %s
                """.formatted(userMessage);

            ExplicitMemoryResult parsed = llmClient.chatWithJsonSchema(
                    null,
                    List.of(new UserMessage(instruction)),
                    Schemas.explicitMemoryResult(),
                    ExplicitMemoryResult.class
            );

            if (!parsed.has_memory()) {
                return Map.of("has_memory", false);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("has_memory", true);
            result.put("slot_name", Optional.ofNullable(parsed.slot_name()).orElse(""));
            result.put("content", Optional.ofNullable(parsed.content()).orElse(""));
            result.put("memory_type", Optional.ofNullable(parsed.memory_type()).orElse("user_insight"));
            result.put("source", Optional.ofNullable(parsed.source()).orElse("explicit"));
            return result;
        } catch (Exception e) {
            log.warn("Structured explicit memory extraction failed; falling back to legacy parsing", e);
            return extractExplicitMemoryLegacy(userMessage);
        }
    }

    private Map<String, Object> extractExplicitMemoryLegacy(String userMessage) {
        String prompt = """
            分析以下用户消息，判断是否包含需要记住的显式信息（如用户偏好、个人信息等）。

            如果包含需要记住的信息，返回 JSON 格式：
            {
              "has_memory": true,
              "slot_name": "槽位名称（如 diet_preference）",
              "content": "记忆内容描述",
              "memory_type": "user_insight",
              "source": "explicit"
            }

            如果不包含，返回：
            {
              "has_memory": false
            }

            用户消息：%s
            """.formatted(userMessage);

        String response = llmClient.chatWithRoleMaps(null, List.of(Map.of("role", "user", "content", prompt)), 0.3);

        try {
            String jsonObject = llmClient.extractJsonObject(response);
            if (jsonObject != null) {
                Map<String, Object> parsed = llmClient.objectMapper.readValue(
                    jsonObject, new TypeReference<Map<String, Object>>() {});

                Object hasMemory = parsed.get("has_memory");
                if (Boolean.TRUE.equals(hasMemory)) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("has_memory", true);
                    result.put("slot_name", parsed.getOrDefault("slot_name", ""));
                    result.put("content", parsed.getOrDefault("content", ""));
                    result.put("memory_type", parsed.getOrDefault("memory_type", "user_insight"));
                    result.put("source", "explicit");
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse explicit memory JSON", e);
        }

        return Map.of("has_memory", false);
    }

    // ========== 用户档案提取 ==========

    public List<Map<String, Object>> extractUserInsights(List<Map<String, Object>> conversationHistory) {
        try {
            String historyText = conversationHistory.stream()
                    .limit(100)
                    .map(entry -> String.format("%s: %s", entry.get("role"), entry.get("message")))
                    .collect(Collectors.joining("\n"));

            String instruction = """
                从以下对话中提取用户的个人信息和偏好，构建用户档案条目。
                - 每条信息一个独立槽位 slot_name
                - confidence 必须是 low/medium/high
                - items 允许为空数组

                对话历史：
                %s
                """.formatted(historyText);

            UserInsightsResult parsed = llmClient.chatWithJsonSchema(
                    null,
                    List.of(new UserMessage(instruction)),
                    Schemas.userInsightsResult(),
                    UserInsightsResult.class
            );

            List<UserInsightItem> items = parsed == null ? null : parsed.items();
            if (items == null || items.isEmpty()) {
                return List.of();
            }

            return items.stream()
                    .map(item -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("slot_name", item.slot_name());
                        m.put("content", item.content());
                        m.put("confidence", item.confidence());
                        return m;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("Structured user insight extraction failed; falling back to legacy parsing", e);
            return extractUserInsightsLegacy(conversationHistory);
        }
    }

    private List<Map<String, Object>> extractUserInsightsLegacy(List<Map<String, Object>> conversationHistory) {
        String historyText = conversationHistory.stream()
            .limit(100)
            .map(entry -> String.format("%s: %s", entry.get("role"), entry.get("message")))
            .collect(Collectors.joining("\n"));

        String prompt = """
            从以下对话中提取用户的个人信息和偏好，构建用户档案。

            返回 JSON 数组，每条信息一个独立槽位：
            [
              {
                "slot_name": "home_city",
                "content": "用户住在某城市",
                "confidence": "high"
              }
            ]

            如果没有发现任何用户信息，返回空数组：[]

            对话历史：
            %s
            """.formatted(historyText);

        String response = llmClient.chatWithRoleMaps(null, List.of(Map.of("role", "user", "content", prompt)), 0.3);

        try {
            String jsonArray = llmClient.extractJsonArray(response);
            if (jsonArray != null && !jsonArray.trim().equals("[]")) {
                List<Map<String, Object>> insights = llmClient.objectMapper.readValue(
                    jsonArray, new TypeReference<List<Map<String, Object>>>() {});
                log.info("Extracted {} user insights", insights.size());
                return insights;
            }
        } catch (Exception e) {
            log.error("Failed to parse user insights JSON", e);
        }

        return new ArrayList<>();
    }

    // ========== 定时任务提取 ==========

    public Map<String, Object> extractScheduledTask(String userMessage, LocalDateTime now, ZoneId zoneId) {
        try {
            String instruction = """
                你是一个“定时任务提取器”。请分析用户消息是否包含需要创建的提醒/定时任务。
                当前本地时间: %s
                当前时区: %s

                返回规则：
                - 如果不需要创建任务：has_task=false，其余字段填空字符串。
                - 如果需要创建任务：has_task=true，并输出 task_title / task_detail / due_at_iso。
                - due_at_iso 必须是绝对时间（不能是“周六/明天”这类相对描述），格式必须为 ISO-8601，
                  例如 2026-03-21T09:00:00。
                - 若用户只给日期未给具体时分，默认时间使用 09:00:00。
                - task_title 要简洁、可读（不超过 20 个字）。

                用户消息：
                %s
                """.formatted(now, zoneId, userMessage);

            ScheduledTaskResult parsed = llmClient.chatWithJsonSchema(
                    null,
                    List.of(new UserMessage(instruction)),
                    Schemas.scheduledTaskResult(),
                    ScheduledTaskResult.class
            );

            if (parsed == null || !parsed.has_task()) {
                return Map.of("has_task", false);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("has_task", true);
            result.put("task_title", Optional.ofNullable(parsed.task_title()).orElse(""));
            result.put("task_detail", Optional.ofNullable(parsed.task_detail()).orElse(""));
            result.put("due_at_iso", Optional.ofNullable(parsed.due_at_iso()).orElse(""));
            return result;
        } catch (Exception e) {
            log.warn("Scheduled task extraction failed", e);
            return Map.of("has_task", false);
        }
    }

    // ========== Memory Reflection ==========

    /**
     * 判断当前用户消息是否需要长期记忆支持。
     *
     * @param instruction 完整的反思提示词
     * @return MemoryReflectionResult 结构化结果
     */
    public MemoryReflectionResult reflectMemoryNeed(String instruction) {
        return llmClient.chatWithJsonSchema(
                null,
                List.of(new UserMessage(instruction)),
                Schemas.memoryReflectionResult(),
                MemoryReflectionResult.class
        );
    }

    // ========== 会话摘要 ==========

    /**
     * 通过 LLM 对一段对话历史生成结构化摘要。
     * Phase 8 核心提取方法。
     *
     * @param conversationText 格式化的对话历史文本
     * @param turnCount 对话轮数
     * @return 结构化摘要结果，失败时返回 null
     */
    public LlmDtos.ConversationSummaryResult summarizeConversation(String conversationText, int turnCount) {
        try {
            String instruction = """
                你是一个对话摘要生成器。请对以下对话历史生成一份简洁的结构化摘要。

                要求：
                - summary：用 2-5 句话概括对话的核心内容、关键决策和结论，使用中文
                - key_topics：提取 3-8 个关键话题词或短语
                - turn_count：对话轮次数（已知为 %d）
                - time_range：对话的时间范围（从对话中提取，格式如 '2026-03-24 20:00 ~ 21:30'；如无法确定填 'unknown'）

                对话历史：
                %s
                """.formatted(turnCount, conversationText);

            return llmClient.chatWithJsonSchema(
                    null,
                    List.of(new UserMessage(instruction)),
                    Schemas.conversationSummaryResult(),
                    LlmDtos.ConversationSummaryResult.class
            );
        } catch (Exception e) {
            log.warn("Conversation summary extraction failed", e);
            return null;
        }
    }

    // ========== 主题切换检测 ==========

    /**
     * 检测当前用户消息是否标志着对话主题的显著切换。
     * Phase 8 #2 — 长对话主题切换时生成 topic summary。
     *
     * @param recentContext 最近几轮对话的上下文文本
     * @param currentMessage 当前用户消息
     * @return 检测结果，失败时返回 null
     */
    public LlmDtos.TopicShiftDetectionResult detectTopicShift(String recentContext, String currentMessage) {
        try {
            String instruction = """
                你是一个对话主题切换检测器。请判断用户的当前消息是否标志着对话主题发生了显著切换。

                判断标准：
                - "显著切换"是指用户从一个话题完全转向了另一个不相关的话题
                - 同一话题下的深入追问、细节补充、修正不算切换
                - 用户打招呼、闲聊、表达感谢后转向新话题算切换
                - 如果最近上下文为空（新对话开始），不算切换

                最近对话上下文：
                %s

                当前用户消息：
                %s

                返回结果：
                - topic_shifted: 是否发生了显著主题切换
                - previous_topic: 如果切换了，简短描述之前的话题（1-10个字）；没有切换填空字符串
                - current_topic: 如果切换了，简短描述当前新话题（1-10个字）；没有切换填空字符串
                """.formatted(recentContext, currentMessage);

            return llmClient.chatWithJsonSchema(
                    null,
                    List.of(new UserMessage(instruction)),
                    Schemas.topicShiftDetectionResult(),
                    LlmDtos.TopicShiftDetectionResult.class
            );
        } catch (Exception e) {
            log.warn("Topic shift detection failed", e);
            return null;
        }
    }

    // ========== 主动提醒生成 ==========

    /**
     * 基于用户画像和会话摘要生成个性化的主动提醒或建议。
     * Phase 9 #5 — 基于记忆生成主动提醒、回顾和建议。
     *
     * @param userProfileText 当前用户画像正文
     * @param recentSummariesText 最近的会话摘要文本
     * @param currentTime 当前时间描述
     * @return 结构化提醒结果，失败时返回 null
     */
    public LlmDtos.ProactiveReminderResult generateProactiveReminder(
            String userProfileText,
            String recentSummariesText,
            String currentTime) {
        try {
            String instruction = """
                你是一个智能记忆助手。请基于用户的画像和最近的对话摘要，判断是否有值得主动提醒用户的内容。

                提醒类型：
                - review（回顾）：提醒用户回顾之前讨论过的重要话题或决策
                - suggestion（建议）：基于用户偏好/习惯给出生活或工作建议
                - follow_up（跟进）：提醒用户跟进之前提到但可能还没完成的事项
                - insight（洞察）：基于用户画像发现的有趣模式或关联
                - none：如果没有值得提醒的内容

                规则：
                - 只有在确实有价值的内容时才 should_remind=true
                - reminder_text 使用中文，语气友好、简洁（2-4句话）
                - 不要编造用户没提过的信息
                - based_on_memories 列出参考了哪些记忆（槽位名或话题关键词）
                - suggested_action 给出一个简短的建议下一步行动（如"可以回顾一下上次的讨论"）

                当前时间：%s

                用户画像：
                %s

                最近会话摘要：
                %s
                """.formatted(currentTime, userProfileText, recentSummariesText);

            return llmClient.chatWithJsonSchema(
                    null,
                    List.of(new UserMessage(instruction)),
                    Schemas.proactiveReminderResult(),
                    LlmDtos.ProactiveReminderResult.class
            );
        } catch (Exception e) {
            log.warn("Proactive reminder generation failed", e);
            return null;
        }
    }
}
