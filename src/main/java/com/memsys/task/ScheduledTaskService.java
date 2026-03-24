package com.memsys.task;

import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.task.model.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ScheduledTaskService {

    private final MemoryStorage storage;
    private final LlmExtractionService extractionService;
    private final Clock clock;
    private final String executionShell;
    private final int executionTimeoutSeconds;
    private final int executionMaxOutputChars;

    public ScheduledTaskService(MemoryStorage storage, LlmExtractionService extractionService) {
        this(storage, extractionService, Clock.systemDefaultZone(), "/bin/zsh", 60, 12000);
    }

    @Autowired
    public ScheduledTaskService(
            MemoryStorage storage,
            LlmExtractionService extractionService,
            @Value("${scheduling.task-execution-shell:/bin/zsh}") String executionShell,
            @Value("${scheduling.task-execution-timeout-seconds:60}") int executionTimeoutSeconds,
            @Value("${scheduling.task-execution-max-output-chars:12000}") int executionMaxOutputChars
    ) {
        this(storage, extractionService, Clock.systemDefaultZone(), executionShell, executionTimeoutSeconds, executionMaxOutputChars);
    }

    ScheduledTaskService(MemoryStorage storage, LlmExtractionService extractionService, Clock clock) {
        this(storage, extractionService, clock, "/bin/zsh", 60, 12000);
    }

    ScheduledTaskService(
            MemoryStorage storage,
            LlmExtractionService extractionService,
            Clock clock,
            String executionShell,
            int executionTimeoutSeconds,
            int executionMaxOutputChars
    ) {
        this.storage = storage;
        this.extractionService = extractionService;
        this.clock = clock;
        this.executionShell = executionShell == null || executionShell.isBlank() ? "/bin/zsh" : executionShell.trim();
        this.executionTimeoutSeconds = Math.max(1, executionTimeoutSeconds);
        this.executionMaxOutputChars = Math.max(200, executionMaxOutputChars);
    }

    public synchronized Optional<ScheduledTask> tryCreateTaskFromMessage(String userMessage) {
        return tryCreateTaskFromMessage(userMessage, "", "", "");
    }

    public synchronized Optional<ScheduledTask> tryCreateTaskFromMessage(
            String userMessage,
            String sourcePlatform,
            String sourceConversationId,
            String sourceSenderId
    ) {
        if (userMessage == null || userMessage.isBlank()) {
            return Optional.empty();
        }

        ZoneId zone = clock.getZone();
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> extraction = extractionService.extractScheduledTask(userMessage, now, zone);
        if (!Boolean.TRUE.equals(extraction.get("has_task"))) {
            return Optional.empty();
        }

        String title = safeTrim(extraction.get("task_title"));
        String detail = safeTrim(extraction.get("task_detail"));
        String dueAtIso = safeTrim(extraction.get("due_at_iso"));
        LocalDateTime dueAt = parseDueAt(dueAtIso, zone);

        if (title.isBlank() || dueAt == null) {
            log.warn("Scheduled task extraction missing fields: {}", extraction);
            return Optional.empty();
        }
        if (dueAt.isBefore(now.minusMinutes(1))) {
            log.info("Ignore task in the past: title={}, dueAt={}", title, dueAt);
            return Optional.empty();
        }

        List<ScheduledTask> tasks = storage.readScheduledTasks();
        boolean duplicated = tasks.stream()
                .anyMatch(task -> ScheduledTask.STATUS_PENDING.equalsIgnoreCase(task.getStatus())
                        && normalized(task.getTitle()).equals(normalized(title))
                        && Objects.equals(task.getDueAt(), dueAt));
        if (duplicated) {
            return Optional.empty();
        }

        ScheduledTask task = new ScheduledTask();
        task.setId(UUID.randomUUID().toString());
        task.setTitle(title);
        task.setDetail(detail);
        task.setDueAt(dueAt);
        task.setCreatedAt(now);
        task.setStatus(ScheduledTask.STATUS_PENDING);
        task.setSourceMessage(userMessage);
        task.setSourcePlatform(safeTrim(sourcePlatform).toLowerCase(Locale.ROOT));
        task.setSourceConversationId(safeTrim(sourceConversationId));
        task.setSourceSenderId(safeTrim(sourceSenderId));

        tasks.add(task);
        storage.writeScheduledTasks(tasks);
        return Optional.of(task);
    }

    public synchronized Optional<ScheduledTask> createTask(
            String title,
            String detail,
            String dueAtIso,
            String executeCommand,
            String sourceMessage,
            String sourcePlatform,
            String sourceConversationId,
            String sourceSenderId
    ) {
        return createTask(title, detail, dueAtIso, executeCommand, null, sourceMessage, sourcePlatform, sourceConversationId, sourceSenderId);
    }

    public synchronized Optional<ScheduledTask> createTask(
            String title,
            String detail,
            String dueAtIso,
            String executeCommand,
            Integer executeTimeoutSeconds,
            String sourceMessage,
            String sourcePlatform,
            String sourceConversationId,
            String sourceSenderId
    ) {
        String cleanTitle = safeTrim(title);
        String cleanDetail = safeTrim(detail);
        String cleanCommand = safeTrim(executeCommand);
        ZoneId zone = clock.getZone();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime dueAt = parseDueAt(dueAtIso, zone);

        if (cleanTitle.isBlank() || dueAt == null) {
            return Optional.empty();
        }
        if (dueAt.isBefore(now.minusMinutes(1))) {
            return Optional.empty();
        }

        List<ScheduledTask> tasks = storage.readScheduledTasks();
        boolean duplicated = tasks.stream()
                .anyMatch(task -> ScheduledTask.STATUS_PENDING.equalsIgnoreCase(task.getStatus())
                        && normalized(task.getTitle()).equals(normalized(cleanTitle))
                        && Objects.equals(task.getDueAt(), dueAt)
                        && normalized(task.getExecuteCommand()).equals(normalized(cleanCommand)));
        if (duplicated) {
            return Optional.empty();
        }

        ScheduledTask task = new ScheduledTask();
        task.setId(UUID.randomUUID().toString());
        task.setTitle(cleanTitle);
        task.setDetail(cleanDetail);
        task.setDueAt(dueAt);
        task.setCreatedAt(now);
        task.setStatus(ScheduledTask.STATUS_PENDING);
        task.setSourceMessage(safeTrim(sourceMessage));
        task.setSourcePlatform(safeTrim(sourcePlatform).toLowerCase(Locale.ROOT));
        task.setSourceConversationId(safeTrim(sourceConversationId));
        task.setSourceSenderId(safeTrim(sourceSenderId));
        task.setExecuteCommand(cleanCommand);
        if (executeTimeoutSeconds != null && executeTimeoutSeconds > 0) {
            task.setExecuteTimeoutSeconds(executeTimeoutSeconds);
        }

        tasks.add(task);
        storage.writeScheduledTasks(tasks);
        return Optional.of(task);
    }

    public synchronized int triggerDueTasks() {
        List<ScheduledTask> tasks = storage.readScheduledTasks();
        if (tasks.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<ScheduledTask> triggered = new ArrayList<>();
        for (ScheduledTask task : tasks) {
            if (!ScheduledTask.STATUS_PENDING.equalsIgnoreCase(task.getStatus())) {
                continue;
            }
            LocalDateTime dueAt = task.getDueAt();
            if (dueAt != null && !dueAt.isAfter(now)) {
                task.setStatus(ScheduledTask.STATUS_TRIGGERED);
                task.setTriggeredAt(now);
                executeTaskCommand(task, now);
                triggered.add(task);
            }
        }

        if (triggered.isEmpty()) {
            return 0;
        }

        storage.writeScheduledTasks(tasks);
        for (ScheduledTask task : triggered) {
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("type", "scheduled_task_due");
            notification.put("task_id", task.getId());
            notification.put("task_title", task.getTitle());
            notification.put("task_detail", task.getDetail());
            notification.put("due_at", task.getDueAt() == null ? null : task.getDueAt().toString());
            notification.put("triggered_at", now.toString());
            notification.put("execute_command", task.getExecuteCommand());
            notification.put("execution_status", task.getExecutionStatus());
            notification.put("execution_exit_code", task.getExecutionExitCode());
            notification.put("execution_output", task.getExecutionOutput());
            notification.put("executed_at", task.getExecutedAt() == null ? null : task.getExecutedAt().toString());
            notification.put("source_platform", task.getSourcePlatform());
            notification.put("source_conversation_id", task.getSourceConversationId());
            notification.put("source_sender_id", task.getSourceSenderId());
            storage.appendPendingTaskNotification(notification);
        }
        return triggered.size();
    }

    public synchronized List<ScheduledTask> listTasks(int limit) {
        List<ScheduledTask> tasks = new ArrayList<>(storage.readScheduledTasks());
        tasks.sort(Comparator
                .comparing((ScheduledTask task) -> task.getDueAt() == null ? LocalDateTime.MAX : task.getDueAt())
                .thenComparing(task -> Optional.ofNullable(task.getCreatedAt()).orElse(LocalDateTime.MIN)));
        if (limit <= 0 || tasks.size() <= limit) {
            return tasks;
        }
        return new ArrayList<>(tasks.subList(0, limit));
    }

    public List<Map<String, Object>> drainPendingNotifications() {
        return storage.drainPendingTaskNotifications();
    }

    public synchronized List<Map<String, Object>> drainPendingNotificationsForConversation(
            String sourcePlatform,
            String sourceConversationId
    ) {
        String normalizedPlatform = normalized(sourcePlatform);
        String normalizedConversationId = safeTrim(sourceConversationId);

        List<Map<String, Object>> all = storage.drainPendingTaskNotifications();
        if (all.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        List<Map<String, Object>> remaining = new ArrayList<>();

        for (Map<String, Object> notification : all) {
            String notificationPlatform = normalized(notification == null ? "" : String.valueOf(notification.getOrDefault("source_platform", "")));
            String notificationConversationId = safeTrim(notification == null ? "" : String.valueOf(notification.getOrDefault("source_conversation_id", "")));

            boolean globalNotification = notificationPlatform.isBlank() || notificationConversationId.isBlank();
            if (normalizedPlatform.isBlank() || normalizedConversationId.isBlank()) {
                if (globalNotification) {
                    selected.add(notification);
                } else {
                    remaining.add(notification);
                }
                continue;
            }

            if (normalizedPlatform.equals(notificationPlatform)
                    && normalizedConversationId.equals(notificationConversationId)) {
                selected.add(notification);
            } else {
                remaining.add(notification);
            }
        }

        requeueNotifications(remaining);
        return selected;
    }

    public synchronized void requeueNotifications(List<Map<String, Object>> notifications) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        for (Map<String, Object> notification : notifications) {
            if (notification == null || notification.isEmpty()) {
                continue;
            }
            storage.appendPendingTaskNotification(notification);
        }
    }

    private String normalized(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private String safeTrim(Object raw) {
        return raw == null ? "" : raw.toString().trim();
    }

    private void executeTaskCommand(ScheduledTask task, LocalDateTime now) {
        String command = safeTrim(task.getExecuteCommand());
        if (command.isBlank()) {
            task.setExecutionStatus("skipped");
            return;
        }

        int timeout = task.getExecuteTimeoutSeconds() != null && task.getExecuteTimeoutSeconds() > 0
                ? task.getExecuteTimeoutSeconds()
                : executionTimeoutSeconds;
        List<String> processCommand = List.of(executionShell, "-lc", command);
        try {
            Process process = new ProcessBuilder(processCommand)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                task.setExecutionStatus("timeout");
                task.setExecutionOutput("Command timed out after " + timeout + " seconds.");
                task.setExecutedAt(now);
                return;
            }

            String output = readProcessOutput(process.getInputStream());
            int exitCode = process.exitValue();
            task.setExecutionStatus(exitCode == 0 ? "success" : "failed");
            task.setExecutionExitCode(exitCode);
            task.setExecutionOutput(truncate(output, executionMaxOutputChars));
            task.setExecutedAt(now);
        } catch (Exception e) {
            task.setExecutionStatus("error");
            task.setExecutionOutput("Command execution failed: " + e.getMessage());
            task.setExecutedAt(now);
            log.warn("Task command execution failed. taskId={}, command={}", task.getId(), command, e);
        }
    }

    private String readProcessOutput(InputStream inputStream) throws Exception {
        try (inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            inputStream.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private LocalDateTime parseDueAt(String dueAtIso, ZoneId zone) {
        if (dueAtIso == null || dueAtIso.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dueAtIso);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(dueAtIso).atZoneSameInstant(zone).toLocalDateTime();
        } catch (DateTimeParseException e) {
            log.warn("Unable to parse due_at_iso: {}", dueAtIso);
            return null;
        }
    }
}
