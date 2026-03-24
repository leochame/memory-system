package com.memsys.memory;

import com.memsys.llm.LlmDtos.ProactiveReminderResult;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.storage.MemoryStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Proactive Reminder Service — Phase 9 #5 核心组件。
 * <p>
 * 基于用户画像和会话摘要，通过 LLM 生成个性化的主动提醒与建议。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>不频繁打扰 — 有最小间隔保护</li>
 *   <li>有据可查 — 每条提醒记录来源和类型</li>
 *   <li>生成失败不阻断 — 全链路 try-catch</li>
 *   <li>可观测 — 提醒记录持久化到 proactive_reminders.jsonl</li>
 * </ul>
 */
@Slf4j
@Service
public class ProactiveReminderService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 两次主动提醒之间的最小间隔（小时） */
    private static final int MIN_INTERVAL_HOURS = 4;

    private final LlmExtractionService extractionService;
    private final MemoryStorage storage;

    /** 上次生成提醒的时间 */
    private volatile LocalDateTime lastReminderTime = null;

    public ProactiveReminderService(
            LlmExtractionService extractionService,
            MemoryStorage storage
    ) {
        this.extractionService = extractionService;
        this.storage = storage;
    }

    /**
     * 尝试生成一条主动提醒。
     * 若距上次提醒不足 MIN_INTERVAL_HOURS 小时，则跳过。
     *
     * @return 生成的提醒文本，null 表示无提醒或跳过
     */
    public String tryGenerateReminder() {
        try {
            // 间隔保护
            if (lastReminderTime != null
                    && lastReminderTime.plusHours(MIN_INTERVAL_HOURS).isAfter(LocalDateTime.now())) {
                log.debug("Proactive reminder skipped: within minimum interval");
                return null;
            }

            // 读取用户画像
            String userProfileText = storage.readUserInsightsNarrative();
            if (userProfileText == null || userProfileText.isBlank()
                    || userProfileText.contains("还没有形成稳定的长期用户画像")) {
                log.debug("Proactive reminder skipped: no user profile available");
                return null;
            }

            // 读取最近会话摘要
            List<Map<String, Object>> recentSummaries = storage.readSessionSummaries(5);
            String summariesText = formatSummaries(recentSummaries);

            // 调用 LLM 生成提醒
            String currentTime = LocalDateTime.now().format(TIME_FORMATTER);
            ProactiveReminderResult result = extractionService.generateProactiveReminder(
                    userProfileText, summariesText, currentTime);

            if (result == null || !result.should_remind()
                    || result.reminder_text() == null || result.reminder_text().isBlank()) {
                log.debug("Proactive reminder: LLM decided no reminder needed");
                return null;
            }

            // 持久化提醒记录
            persistReminder(result);
            lastReminderTime = LocalDateTime.now();

            log.info("Proactive reminder generated: type={}, based_on={}",
                    result.reminder_type(),
                    result.based_on_memories());

            return result.reminder_text();

        } catch (Exception e) {
            log.warn("Proactive reminder generation failed", e);
            return null;
        }
    }

    /**
     * 获取最近的提醒记录，供 CLI 展示。
     *
     * @param limit 最多返回条数
     * @return 提醒记录列表
     */
    public List<Map<String, Object>> getRecentReminders(int limit) {
        return storage.readProactiveReminders(limit);
    }

    /**
     * 获取上次生成提醒的时间。
     */
    public LocalDateTime getLastReminderTime() {
        return lastReminderTime;
    }

    private void persistReminder(ProactiveReminderResult result) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("generated_at", LocalDateTime.now().toString());
            record.put("reminder_text", result.reminder_text());
            record.put("reminder_type", result.reminder_type());
            record.put("based_on_memories", result.based_on_memories() != null
                    ? result.based_on_memories() : List.of());
            record.put("suggested_action", result.suggested_action() != null
                    ? result.suggested_action() : "");
            storage.appendProactiveReminder(record);
        } catch (Exception e) {
            log.warn("Failed to persist proactive reminder", e);
        }
    }

    private String formatSummaries(List<Map<String, Object>> summaries) {
        if (summaries == null || summaries.isEmpty()) {
            return "（暂无会话摘要）";
        }
        return summaries.stream()
                .map(s -> {
                    String time = String.valueOf(s.getOrDefault("generated_at", "unknown"));
                    String summary = String.valueOf(s.getOrDefault("summary", ""));
                    Object topics = s.get("key_topics");
                    String topicsStr = topics instanceof List<?> list
                            ? list.stream().map(String::valueOf).collect(Collectors.joining(", "))
                            : "";
                    return String.format("[%s] %s (话题: %s)", time, summary, topicsStr);
                })
                .collect(Collectors.joining("\n"));
    }
}
