package com.memsys.task;

import com.memsys.im.ImRuntimeService;
import com.memsys.memory.MemoryScopeContext;
import com.memsys.memory.storage.MemoryStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledTaskReminderJob {

    private static final DateTimeFormatter TASK_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ScheduledTaskService scheduledTaskService;
    private final ImRuntimeService imRuntimeService;
    private final MemoryStorage memoryStorage;
    private final boolean imReminderEnabled;

    public ScheduledTaskReminderJob(
            ScheduledTaskService scheduledTaskService,
            ImRuntimeService imRuntimeService,
            MemoryStorage memoryStorage,
            @Value("${scheduling.im-reminder-enabled:true}") boolean imReminderEnabled
    ) {
        this.scheduledTaskService = scheduledTaskService;
        this.imRuntimeService = imRuntimeService;
        this.memoryStorage = memoryStorage;
        this.imReminderEnabled = imReminderEnabled;
    }

    @Scheduled(fixedDelayString = "${scheduling.task-reminder-check-ms:30000}")
    public void triggerDueTasks() {
        int totalTriggered = 0;
        int totalPushed = 0;
        int totalRequeued = 0;
        for (String scope : memoryStorage.listKnownScopes()) {
            int triggeredCount;
            List<Map<String, Object>> notifications;
            try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(scope)) {
                triggeredCount = scheduledTaskService.triggerDueTasks();
                notifications = scheduledTaskService.drainPendingNotifications();
            }

            totalTriggered += triggeredCount;
            if (notifications.isEmpty()) {
                continue;
            }

            int pushedCount = 0;
            List<Map<String, Object>> remaining = new ArrayList<>();
            for (Map<String, Object> notification : notifications) {
                String platform = trim(notification.get("source_platform")).toLowerCase();
                String conversationId = trim(notification.get("source_conversation_id"));
                if (platform.isBlank() || conversationId.isBlank()) {
                    remaining.add(notification);
                    continue;
                }
                if (!imReminderEnabled) {
                    remaining.add(notification);
                    continue;
                }
                String text = buildReminderText(notification);
                try {
                    imRuntimeService.sendText(platform, conversationId, text);
                    pushedCount++;
                } catch (Exception e) {
                    log.warn("Failed to push IM reminder. platform={}, conversationId={}", platform, conversationId, e);
                    remaining.add(notification);
                }
            }
            totalPushed += pushedCount;
            totalRequeued += remaining.size();
            if (!remaining.isEmpty()) {
                try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(scope)) {
                    scheduledTaskService.requeueNotifications(remaining);
                }
            }
        }
        if (totalTriggered > 0 || totalPushed > 0 || totalRequeued > 0) {
            log.info("Scheduled task reminders processed. triggered={}, pushed={}, requeued={}",
                    totalTriggered, totalPushed, totalRequeued);
        }
    }

    private String buildReminderText(Map<String, Object> notification) {
        String title = trim(notification.get("task_title"));
        if (title.isBlank()) {
            title = "未命名任务";
        }
        String detail = trim(notification.get("task_detail"));
        String dueAt = formatTaskTime(trim(notification.get("due_at")));
        String executionStatus = trim(notification.get("execution_status"));
        String executionExitCode = trim(notification.get("execution_exit_code"));
        String executionOutput = trim(notification.get("execution_output"));
        String executionPart = buildExecutionPart(executionStatus, executionExitCode, executionOutput);
        if (detail.isBlank()) {
            return "[提醒] 任务已到时间：" + title + "（计划时间 " + dueAt + "）" + executionPart;
        }
        return "[提醒] 任务已到时间：" + title + "（计划时间 " + dueAt + "）\n详情：" + detail + executionPart;
    }

    private String buildExecutionPart(String status, String exitCode, String output) {
        if (status.isBlank() || "skipped".equalsIgnoreCase(status)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n执行状态：").append(status);
        if (!exitCode.isBlank()) {
            sb.append(" (exit_code=").append(exitCode).append(")");
        }
        if (!output.isBlank()) {
            sb.append("\n执行输出：").append(truncate(output, 400));
        }
        return sb.toString();
    }

    private String truncate(String text, int limit) {
        if (text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "...";
    }

    private String formatTaskTime(String raw) {
        if (raw.isBlank()) {
            return "未知时间";
        }
        try {
            return LocalDateTime.parse(raw).format(TASK_TIME_FORMATTER);
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
