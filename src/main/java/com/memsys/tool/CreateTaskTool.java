package com.memsys.tool;

import com.memsys.llm.LlmClient;
import com.memsys.task.ScheduledTaskService;
import com.memsys.task.model.ScheduledTask;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 按需创建定时任务。
 * 任务是否应该创建由 LLM 提取结果决定，不依赖关键词匹配。
 */
@Component
public class CreateTaskTool extends BaseTool {

    private static final DateTimeFormatter TASK_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final ScheduledTaskService scheduledTaskService;

    public CreateTaskTool(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    @Override
    public Optional<LlmClient.ToolDefinition> build(List<String> availableSkillNames) {
        if (scheduledTaskService == null) {
            return Optional.empty();
        }

        ToolSpecification spec = ToolSpecification.builder()
                .name("create_task")
                .description("创建定时任务。支持两种模式：1) 传 user_message 由模型解析时间；2) 由前端直接传 title/due_at_iso/execute_command。")
                .addParameter(
                        "user_message",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选。用户原始消息，用于解析任务标题与执行时间。")
                )
                .addParameter(
                        "title",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选。任务标题；当前端已明确任务信息时直接传入。")
                )
                .addParameter(
                        "detail",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选。任务详情。")
                )
                .addParameter(
                        "due_at_iso",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选。任务执行时间，ISO-8601，例如 2026-03-21T09:00:00。")
                )
                .addParameter(
                        "execute_command",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选。到点后在本地 shell 执行的命令。为空则仅提醒。")
                )
                .addParameter(
                        "execute_timeout_seconds",
                        JsonSchemaProperty.INTEGER,
                        JsonSchemaProperty.description("可选。命令执行超时秒数（整数），未传则使用系统默认值。")
                )
                .addParameter(
                        "recurrence_type",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选。重复类型：none/daily/weekly。默认 none。")
                )
                .addParameter(
                        "recurrence_interval",
                        JsonSchemaProperty.INTEGER,
                        JsonSchemaProperty.description("可选。重复间隔（整数）。daily=天数间隔，weekly=周数间隔，默认 1。")
                )
                .addParameter(
                        "source_platform",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选，会话来源平台（如 telegram/feishu）。缺省时使用当前会话上下文。")
                )
                .addParameter(
                        "source_conversation_id",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选，会话 ID。缺省时使用当前会话上下文。")
                )
                .addParameter(
                        "source_sender_id",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选，发送者 ID。缺省时使用当前会话上下文。")
                )
                .build();
        return Optional.of(new LlmClient.ToolDefinition(spec, this::execute));
    }

    private String execute(ToolExecutionRequest request) {
        Map<String, Object> args = parseArguments(request);
        String userMessage = stringArg(request, "user_message");
        String title = stringArg(request, "title");
        String detail = stringArg(request, "detail");
        String dueAtIso = stringArg(request, "due_at_iso");
        String executeCommand = stringArg(request, "execute_command");
        Integer executeTimeoutSeconds = intArg(args, "execute_timeout_seconds");
        String recurrenceType = stringArg(request, "recurrence_type");
        Integer recurrenceInterval = intArg(args, "recurrence_interval");

        ToolRuntimeContext.TaskSourceContext context = ToolRuntimeContext.taskSourceContext();
        String sourcePlatform = firstNonBlank(stringArg(request, "source_platform"), context.sourcePlatform());
        String sourceConversationId = firstNonBlank(stringArg(request, "source_conversation_id"), context.sourceConversationId());
        String sourceSenderId = firstNonBlank(stringArg(request, "source_sender_id"), context.sourceSenderId());

        Optional<ScheduledTask> created;
        // 仅当显式提供任务核心字段时才走直传模式，避免 execute_command 单独出现时误跳过 user_message 解析。
        boolean directMode = !title.isBlank() || !dueAtIso.isBlank();
        if (directMode) {
            created = scheduledTaskService.createTask(
                    title,
                    detail,
                    dueAtIso,
                    executeCommand,
                    executeTimeoutSeconds,
                    recurrenceType,
                    recurrenceInterval,
                    userMessage,
                    sourcePlatform,
                    sourceConversationId,
                    sourceSenderId
            );
        } else {
            if (userMessage.isBlank()) {
                return "create_task 调用失败：缺少参数。请提供 user_message 或 title/due_at_iso。";
            }
            created = scheduledTaskService.tryCreateTaskFromMessage(
                    userMessage,
                    sourcePlatform,
                    sourceConversationId,
                    sourceSenderId
            );
        }

        if (created.isEmpty()) {
            return "create_task 未创建任务：缺少必要字段（title/due_at_iso），或任务重复/时间已过。";
        }

        ScheduledTask task = created.get();
        String dueAt = task.getDueAt() == null ? "unknown" : task.getDueAt().format(TASK_TIME_FORMATTER);
        String shortId = task.getId() == null ? "-" : task.getId().substring(0, Math.min(task.getId().length(), 8));
        log.info("Executed tool create_task(id={}, title={}, dueAt={})", shortId, task.getTitle(), dueAt);
        return "create_task 成功：id=" + shortId
                + ", title=" + safeText(task.getTitle())
                + ", due_at=" + dueAt;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(ToolExecutionRequest request) {
        if (request == null || request.arguments() == null || request.arguments().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(request.arguments(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Integer intArg(Map<String, Object> args, String fieldName) {
        if (args == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }
        Object value = args.get(fieldName);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String safeText(String value) {
        return value == null || value.isBlank() ? "(untitled)" : value.trim();
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }
}
