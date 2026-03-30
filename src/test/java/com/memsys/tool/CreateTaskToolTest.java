package com.memsys.tool;

import com.memsys.llm.LlmClient;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.task.ScheduledTaskService;
import com.memsys.task.model.ScheduledTask;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateTaskToolTest {

    @TempDir
    Path tempDir;

    @Test
    void createTaskToolUsesConversationSourceContext() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.extractScheduledTask(anyString(), any(LocalDateTime.class), any(ZoneId.class)))
                .thenReturn(Map.of(
                        "has_task", true,
                        "task_title", "周会",
                        "task_detail", "项目同步",
                        "due_at_iso", "2099-03-21T09:00:00"
                ));

        ScheduledTaskService service = new ScheduledTaskService(storage, extractionService);
        CreateTaskTool tool = new CreateTaskTool(service);
        LlmClient.ToolDefinition definition = tool.build(List.of()).orElseThrow();

        String result;
        try (ToolRuntimeContext.Scope ignored = ToolRuntimeContext.bindTaskSourceContext(
                "telegram", "chat-123", "user-9"
        )) {
            result = definition.executor().apply(ToolExecutionRequest.builder()
                    .id("1")
                    .name("create_task")
                    .arguments("{\"user_message\":\"明天提醒我开周会\"}")
                    .build());
        }

        assertThat(result).contains("create_task 成功");
        List<ScheduledTask> tasks = storage.readScheduledTasks();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getSourcePlatform()).isEqualTo("telegram");
        assertThat(tasks.get(0).getSourceConversationId()).isEqualTo("chat-123");
        assertThat(tasks.get(0).getSourceSenderId()).isEqualTo("user-9");
    }

    @Test
    void createTaskToolReturnsSkippedWhenExtractionHasNoTask() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        when(extractionService.extractScheduledTask(anyString(), any(LocalDateTime.class), any(ZoneId.class)))
                .thenReturn(Map.of("has_task", false));

        ScheduledTaskService service = new ScheduledTaskService(storage, extractionService);
        CreateTaskTool tool = new CreateTaskTool(service);
        LlmClient.ToolDefinition definition = tool.build(List.of()).orElseThrow();

        String result = definition.executor().apply(ToolExecutionRequest.builder()
                .id("2")
                .name("create_task")
                .arguments("{\"user_message\":\"只是聊聊，不建任务\"}")
                .build());

        assertThat(result).contains("未创建任务");
        assertThat(storage.readScheduledTasks()).isEmpty();
    }

    @Test
    void createTaskToolSupportsDirectFrontPayload() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        ScheduledTaskService service = new ScheduledTaskService(storage, extractionService);
        CreateTaskTool tool = new CreateTaskTool(service);
        LlmClient.ToolDefinition definition = tool.build(List.of()).orElseThrow();

        String result = definition.executor().apply(ToolExecutionRequest.builder()
                .id("3")
                .name("create_task")
                .arguments("""
                        {"title":"前端任务","detail":"直接建任务","due_at_iso":"2099-03-21T09:00:00","execute_command":"echo hello-from-front"}
                        """)
                .build());

        assertThat(result).contains("create_task 成功");
        List<ScheduledTask> tasks = storage.readScheduledTasks();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTitle()).isEqualTo("前端任务");
        assertThat(tasks.get(0).getExecuteCommand()).isEqualTo("echo hello-from-front");
    }

    @Test
    void createTaskToolSupportsRecurringTaskPayload() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LlmExtractionService extractionService = mock(LlmExtractionService.class);
        ScheduledTaskService service = new ScheduledTaskService(storage, extractionService);
        CreateTaskTool tool = new CreateTaskTool(service);
        LlmClient.ToolDefinition definition = tool.build(List.of()).orElseThrow();

        String result = definition.executor().apply(ToolExecutionRequest.builder()
                .id("4")
                .name("create_task")
                .arguments("""
                        {"title":"每周复盘","detail":"周会前准备","due_at_iso":"2099-03-21T09:00:00","recurrence_type":"weekly","recurrence_interval":1}
                        """)
                .build());

        assertThat(result).contains("create_task 成功");
        List<ScheduledTask> tasks = storage.readScheduledTasks();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getRecurrenceType()).isEqualTo("weekly");
        assertThat(tasks.get(0).getRecurrenceInterval()).isEqualTo(1);
    }
}
