package com.memsys.task;

import com.memsys.im.ImRuntimeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ScheduledTaskReminderJobTest {

    @Test
    void triggerDueTasksShouldPushImNotificationsAndRequeueUnsent() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ImRuntimeService imRuntimeService = mock(ImRuntimeService.class);

        when(taskService.triggerDueTasks()).thenReturn(1);
        when(taskService.drainPendingNotifications()).thenReturn(List.of(
                Map.of(
                        "task_title", "晨会",
                        "due_at", "2026-03-20T09:30:00",
                        "source_platform", "telegram",
                        "source_conversation_id", "chat-1"
                ),
                Map.of(
                        "task_title", "本地提醒"
                )
        ));

        ScheduledTaskReminderJob job = new ScheduledTaskReminderJob(taskService, imRuntimeService, true);
        job.triggerDueTasks();

        verify(imRuntimeService, times(1)).sendText(eq("telegram"), eq("chat-1"), anyString());
        verify(taskService, times(1)).requeueNotifications(argThat(items ->
                items != null
                        && items.size() == 1
                        && "本地提醒".equals(String.valueOf(items.get(0).get("task_title")))
        ));
    }

    @Test
    void triggerDueTasksShouldRequeueWhenImReminderDisabled() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ImRuntimeService imRuntimeService = mock(ImRuntimeService.class);

        when(taskService.triggerDueTasks()).thenReturn(1);
        when(taskService.drainPendingNotifications()).thenReturn(List.of(
                Map.of(
                        "task_title", "晨会",
                        "source_platform", "telegram",
                        "source_conversation_id", "chat-1"
                )
        ));

        ScheduledTaskReminderJob job = new ScheduledTaskReminderJob(taskService, imRuntimeService, false);
        job.triggerDueTasks();

        verify(imRuntimeService, never()).sendText(anyString(), anyString(), anyString());
        verify(taskService, times(1)).requeueNotifications(argThat(items -> items != null && items.size() == 1));
    }
}

