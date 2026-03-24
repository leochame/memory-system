package com.memsys.task;

import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.task.model.ScheduledTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScheduledTaskServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void tryCreateTaskFromMessageCreatesTaskAndPersists() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.extractScheduledTask(anyString(), any(LocalDateTime.class), any(ZoneId.class)))
                .thenReturn(Map.of(
                        "has_task", true,
                        "task_title", "周六会议",
                        "task_detail", "项目周会",
                        "due_at_iso", "2026-03-21T09:00:00"
                ));

        ScheduledTaskService service = new ScheduledTaskService(
                storage,
                extractionService,
                Clock.fixed(Instant.parse("2026-03-19T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );

        Optional<ScheduledTask> created = service.tryCreateTaskFromMessage("这周六有个会");

        assertThat(created).isPresent();
        List<ScheduledTask> tasks = storage.readScheduledTasks();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTitle()).isEqualTo("周六会议");
        assertThat(tasks.get(0).getStatus()).isEqualTo(ScheduledTask.STATUS_PENDING);
    }

    @Test
    void tryCreateTaskFromMessageDoesNotRequireKeywordHints() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.extractScheduledTask(anyString(), any(LocalDateTime.class), any(ZoneId.class)))
                .thenReturn(Map.of(
                        "has_task", true,
                        "task_title", "提交周报",
                        "task_detail", "在会议前提交",
                        "due_at_iso", "2026-03-21T09:00:00"
                ));

        ScheduledTaskService service = new ScheduledTaskService(
                storage,
                extractionService,
                Clock.fixed(Instant.parse("2026-03-19T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );

        Optional<ScheduledTask> created = service.tryCreateTaskFromMessage("把这件事安排一下");

        assertThat(created).isPresent();
        assertThat(storage.readScheduledTasks()).hasSize(1);
        assertThat(storage.readScheduledTasks().get(0).getTitle()).isEqualTo("提交周报");
    }

    @Test
    void tryCreateTaskFromImMessagePersistsSourceConversation() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.extractScheduledTask(anyString(), any(LocalDateTime.class), any(ZoneId.class)))
                .thenReturn(Map.of(
                        "has_task", true,
                        "task_title", "明早站会",
                        "task_detail", "提醒我参加早会",
                        "due_at_iso", "2026-03-20T09:30:00"
                ));

        ScheduledTaskService service = new ScheduledTaskService(
                storage,
                extractionService,
                Clock.fixed(Instant.parse("2026-03-20T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );

        Optional<ScheduledTask> created = service.tryCreateTaskFromMessage(
                "明天早上九点半提醒我开会",
                "telegram",
                "chat-123",
                "user-888"
        );

        assertThat(created).isPresent();
        ScheduledTask task = storage.readScheduledTasks().get(0);
        assertThat(task.getSourcePlatform()).isEqualTo("telegram");
        assertThat(task.getSourceConversationId()).isEqualTo("chat-123");
        assertThat(task.getSourceSenderId()).isEqualTo("user-888");
    }

    @Test
    void tryCreateTaskFromMessageSkipsDuplicateTask() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.extractScheduledTask(anyString(), any(LocalDateTime.class), any(ZoneId.class)))
                .thenReturn(Map.of(
                        "has_task", true,
                        "task_title", "周六会议",
                        "task_detail", "项目周会",
                        "due_at_iso", "2026-03-21T09:00:00"
                ));

        ScheduledTaskService service = new ScheduledTaskService(
                storage,
                extractionService,
                Clock.fixed(Instant.parse("2026-03-19T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );

        service.tryCreateTaskFromMessage("这周六有个会");
        service.tryCreateTaskFromMessage("这周六有个会");

        assertThat(storage.readScheduledTasks()).hasSize(1);
    }

    @Test
    void triggerDueTasksWritesNotificationsAndUpdatesStatus() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        ScheduledTaskService service = new ScheduledTaskService(
                storage,
                extractionService,
                Clock.fixed(Instant.parse("2026-03-19T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );

        ScheduledTask task = new ScheduledTask();
        task.setId("task-1");
        task.setTitle("早会");
        task.setDetail("项目同步");
        task.setCreatedAt(LocalDateTime.of(2026, 3, 19, 8, 0));
        task.setDueAt(LocalDateTime.of(2026, 3, 19, 8, 30));
        task.setStatus(ScheduledTask.STATUS_PENDING);
        task.setSourcePlatform("telegram");
        task.setSourceConversationId("chat-1");
        task.setSourceSenderId("user-1");
        storage.writeScheduledTasks(List.of(task));

        int triggered = service.triggerDueTasks();

        assertThat(triggered).isEqualTo(1);
        assertThat(storage.readScheduledTasks().get(0).getStatus()).isEqualTo(ScheduledTask.STATUS_TRIGGERED);
        List<Map<String, Object>> notifications = service.drainPendingNotifications();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0)).containsEntry("task_title", "早会");
        assertThat(notifications.get(0)).containsEntry("source_platform", "telegram");
        assertThat(notifications.get(0)).containsEntry("source_conversation_id", "chat-1");
        assertThat(notifications.get(0)).containsEntry("source_sender_id", "user-1");
    }

    @Test
    void drainPendingNotificationsForConversationShouldFilterBySourceContext() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        ScheduledTaskService service = new ScheduledTaskService(
                storage,
                extractionService,
                Clock.fixed(Instant.parse("2026-03-19T01:00:00Z"), ZoneId.of("Asia/Shanghai"))
        );

        storage.appendPendingTaskNotification(Map.of(
                "task_title", "telegram-task",
                "source_platform", "telegram",
                "source_conversation_id", "chat-1"
        ));
        storage.appendPendingTaskNotification(Map.of(
                "task_title", "feishu-task",
                "source_platform", "feishu",
                "source_conversation_id", "oc_xxx"
        ));
        storage.appendPendingTaskNotification(Map.of(
                "task_title", "cli-task"
        ));

        List<Map<String, Object>> telegramNotifs = service.drainPendingNotificationsForConversation("telegram", "chat-1");
        assertThat(telegramNotifs).hasSize(1);
        assertThat(telegramNotifs.get(0)).containsEntry("task_title", "telegram-task");

        List<Map<String, Object>> cliNotifs = service.drainPendingNotificationsForConversation("", "");
        assertThat(cliNotifs).hasSize(1);
        assertThat(cliNotifs.get(0)).containsEntry("task_title", "cli-task");

        List<Map<String, Object>> remains = service.drainPendingNotifications();
        assertThat(remains).hasSize(1);
        assertThat(remains.get(0)).containsEntry("task_title", "feishu-task");
    }

    @Test
    void createTaskWithCommandShouldExecuteWhenDue() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        ScheduledTaskService service = new ScheduledTaskService(
                storage,
                extractionService,
                Clock.fixed(Instant.parse("2026-03-19T01:00:00Z"), ZoneId.of("Asia/Shanghai")),
                "/bin/sh",
                10,
                2000
        );

        Optional<ScheduledTask> created = service.createTask(
                "执行 shell 命令",
                "测试任务",
                "2026-03-19T09:00:00",
                "echo task-ok",
                "front payload",
                "telegram",
                "chat-66",
                "user-77"
        );
        assertThat(created).isPresent();

        int triggered = service.triggerDueTasks();
        assertThat(triggered).isEqualTo(1);

        ScheduledTask task = storage.readScheduledTasks().get(0);
        assertThat(task.getStatus()).isEqualTo(ScheduledTask.STATUS_TRIGGERED);
        assertThat(task.getExecutionStatus()).isEqualTo("success");
        assertThat(task.getExecutionExitCode()).isEqualTo(0);
        assertThat(task.getExecutionOutput()).contains("task-ok");

        List<Map<String, Object>> notifications = service.drainPendingNotifications();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0)).containsEntry("execution_status", "success");
    }
}
