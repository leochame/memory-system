package com.memsys.memory;

import com.memsys.identity.UserIdentityService;
import com.memsys.identity.model.UserIdentity;
import com.memsys.im.ImRuntimeService;
import com.memsys.llm.LlmClient;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.task.model.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

@Slf4j
@Service
public class WeeklyReviewService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MemoryStorage storage;
    private final UserIdentityService userIdentityService;
    private final LlmClient llmClient;
    private final ObjectProvider<ImRuntimeService> imRuntimeServiceProvider;
    private final boolean weeklyReviewEnabled;
    private final boolean weeklyReviewPushFeishuEnabled;

    public WeeklyReviewService(
            MemoryStorage storage,
            UserIdentityService userIdentityService,
            LlmClient llmClient,
            ObjectProvider<ImRuntimeService> imRuntimeServiceProvider,
            @Value("${scheduling.weekly-review-enabled:true}") boolean weeklyReviewEnabled,
            @Value("${scheduling.weekly-review-push-feishu-enabled:true}") boolean weeklyReviewPushFeishuEnabled
    ) {
        this.storage = storage;
        this.userIdentityService = userIdentityService;
        this.llmClient = llmClient;
        this.imRuntimeServiceProvider = imRuntimeServiceProvider;
        this.weeklyReviewEnabled = weeklyReviewEnabled;
        this.weeklyReviewPushFeishuEnabled = weeklyReviewPushFeishuEnabled;
    }

    public boolean isWeeklyReviewEnabled() {
        return weeklyReviewEnabled;
    }

    public String generateCurrentScopeReview() {
        return generateScopeReview(MemoryScopeContext.currentScope(), "当前用户");
    }

    public WeeklyPushResult generateAndPushAllPersonalReviews() {
        if (!weeklyReviewEnabled) {
            return new WeeklyPushResult(0, 0, 0);
        }
        int generated = 0;
        int pushed = 0;
        int failedPush = 0;

        for (UserIdentity identity : userIdentityService.listAllIdentities()) {
            String unifiedId = safeTrim(identity.getUnifiedId());
            if (unifiedId.isBlank()) {
                continue;
            }
            String scope = MemoryScopeContext.personalScope(unifiedId);
            String report;
            try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(scope)) {
                String displayName = safeTrim(identity.getDisplayName());
                if (displayName.isBlank()) {
                    displayName = unifiedId;
                }
                report = generateScopeReview(scope, displayName);
            }
            generated++;

            if (!weeklyReviewPushFeishuEnabled) {
                continue;
            }
            String conversationId = userIdentityService.getConversationChannel(unifiedId, "feishu");
            if (conversationId.isBlank()) {
                continue;
            }
            ImRuntimeService imRuntimeService = imRuntimeServiceProvider.getIfAvailable();
            if (imRuntimeService == null) {
                failedPush++;
                continue;
            }
            try {
                imRuntimeService.sendText("feishu", conversationId, report);
                pushed++;
            } catch (Exception e) {
                failedPush++;
                log.warn("Failed to push weekly review to feishu. unifiedId={}, conversationId={}",
                        unifiedId, conversationId, e);
            }
        }
        return new WeeklyPushResult(generated, pushed, failedPush);
    }

    private String generateScopeReview(String scope, String displayName) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekAgo = now.minusDays(7);

        List<ScheduledTask> tasks = storage.readScheduledTasks();
        int completed = 0;
        int delayed = 0;
        int dueNextWeek = 0;
        for (ScheduledTask task : tasks) {
            if (task == null) {
                continue;
            }
            if (isTriggeredInRange(task, weekAgo, now)) {
                completed++;
            }
            if (isDelayed(task, now)) {
                delayed++;
            }
            if (isDueInRange(task.getDueAt(), now, now.plusDays(7))) {
                dueNextWeek++;
            }
        }

        List<Map<String, Object>> summaries = storage.readSessionSummaries(0);
        Map<String, Integer> topicCount = new HashMap<>();
        for (Map<String, Object> summary : summaries) {
            LocalDateTime generatedAt = parseDateTime(summary.get("generated_at"));
            if (generatedAt == null || generatedAt.isBefore(weekAgo)) {
                continue;
            }
            Object topicsObj = summary.get("key_topics");
            if (topicsObj instanceof List<?> topicList) {
                for (Object topic : topicList) {
                    String value = safeTrim(topic);
                    if (value.isBlank()) {
                        continue;
                    }
                    topicCount.put(value, topicCount.getOrDefault(value, 0) + 1);
                }
            }
        }

        List<Map.Entry<String, Integer>> sortedTopics = topicCount.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(3)
                .toList();
        List<String> topTopics = sortedTopics.stream()
                .map(entry -> entry.getKey() + "(" + entry.getValue() + ")")
                .toList();

        List<String> suggestions = generateSuggestions(displayName, completed, delayed, dueNextWeek, topTopics);
        String topicsText = topTopics.isEmpty() ? "暂无显著高频主题" : String.join("、", topTopics);
        String report = """
                【每周个人复盘】%s
                统计周期：%s ~ %s

                ✅ 完成任务：%d
                ⏰ 拖延/逾期任务：%d
                📌 下周待关注任务：%d
                🔍 高频主题：%s

                📣 下周建议：
                1) %s
                2) %s
                3) %s
                """.formatted(
                displayName,
                weekAgo.format(DateTimeFormatter.ofPattern("MM-dd")),
                now.format(DateTimeFormatter.ofPattern("MM-dd")),
                completed,
                delayed,
                dueNextWeek,
                topicsText,
                suggestions.get(0),
                suggestions.get(1),
                suggestions.get(2)
        );

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("generated_at", now.toString());
        review.put("scope", scope);
        review.put("completed_tasks", completed);
        review.put("delayed_tasks", delayed);
        review.put("due_next_week", dueNextWeek);
        review.put("top_topics", topTopics);
        review.put("suggestions", suggestions);
        review.put("report_text", report);
        storage.appendWeeklyReview(review);

        return report;
    }

    private boolean isTriggeredInRange(ScheduledTask task, LocalDateTime start, LocalDateTime end) {
        if (task == null) {
            return false;
        }
        LocalDateTime triggeredAt = task.getTriggeredAt();
        if (triggeredAt == null) {
            return false;
        }
        return !triggeredAt.isBefore(start) && !triggeredAt.isAfter(end);
    }

    private boolean isDelayed(ScheduledTask task, LocalDateTime now) {
        if (task == null) {
            return false;
        }
        return ScheduledTask.STATUS_PENDING.equalsIgnoreCase(safeTrim(task.getStatus()))
                && task.getDueAt() != null
                && task.getDueAt().isBefore(now);
    }

    private boolean isDueInRange(LocalDateTime dueAt, LocalDateTime start, LocalDateTime end) {
        if (dueAt == null) {
            return false;
        }
        return !dueAt.isBefore(start) && !dueAt.isAfter(end);
    }

    private List<String> generateSuggestions(String displayName,
                                             int completed,
                                             int delayed,
                                             int dueNextWeek,
                                             List<String> topTopics) {
        try {
            String topicsText = topTopics.isEmpty() ? "暂无显著高频主题" : String.join("、", topTopics);
            String prompt = """
                    你是个人效率教练。请基于以下周报数据，输出 3 条下周建议，每条不超过 28 字。
                    仅输出三行，不要加序号，不要解释。

                    用户: %s
                    本周完成任务: %d
                    当前拖延任务: %d
                    下周待关注任务: %d
                    高频主题: %s
                    """.formatted(displayName, completed, delayed, dueNextWeek, topicsText);

            String raw = llmClient.chat(null, List.of(new dev.langchain4j.data.message.UserMessage(prompt)), 0.3);
            List<String> lines = Arrays.stream(raw.replace("\r\n", "\n").split("\n"))
                    .map(String::trim)
                    .filter(line -> !line.isBlank())
                    .map(line -> line.replaceFirst("^[-*\\d.\\s]+", ""))
                    .filter(line -> !line.isBlank())
                    .limit(3)
                    .toList();
            if (lines.size() == 3) {
                return lines;
            }
        } catch (Exception e) {
            log.debug("Generate weekly suggestions via LLM failed", e);
        }
        List<String> fallback = new ArrayList<>();
        fallback.add(delayed > 0 ? "优先清掉逾期任务，再规划新增事项" : "保持本周节奏，先安排下周三件要事");
        fallback.add(dueNextWeek > 0 ? "把下周任务按轻重缓急拆分到每天" : "预留固定复盘时间，巩固执行节奏");
        fallback.add(topTopics.isEmpty() ? "补充高价值主题输入，形成稳定主线" : "围绕高频主题做一次深度总结与行动");
        return fallback;
    }

    private LocalDateTime parseDateTime(Object value) {
        String text = safeTrim(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text, DATE_TIME);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String safeTrim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record WeeklyPushResult(int generated, int pushed, int failedPush) {
    }
}
