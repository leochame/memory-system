package com.memsys.tool;

/**
 * 对话工具运行时上下文（线程内）：
 * 用于将当前会话来源信息透传给工具执行层。
 */
public final class ToolRuntimeContext {

    private static final ThreadLocal<TaskSourceContext> TASK_SOURCE_CONTEXT =
            ThreadLocal.withInitial(TaskSourceContext::empty);

    private ToolRuntimeContext() {
    }

    public static Scope bindTaskSourceContext(String sourcePlatform,
                                              String sourceConversationId,
                                              String sourceSenderId) {
        return bindTaskSourceContext(sourcePlatform, sourceConversationId, sourceSenderId, false);
    }

    public static Scope bindTaskSourceContext(String sourcePlatform,
                                              String sourceConversationId,
                                              String sourceSenderId,
                                              boolean commandExecutionAllowed) {
        TASK_SOURCE_CONTEXT.set(new TaskSourceContext(
                safeTrim(sourcePlatform),
                safeTrim(sourceConversationId),
                safeTrim(sourceSenderId),
                commandExecutionAllowed
        ));
        return new Scope();
    }

    public static TaskSourceContext taskSourceContext() {
        return TASK_SOURCE_CONTEXT.get();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    public record TaskSourceContext(
            String sourcePlatform,
            String sourceConversationId,
            String sourceSenderId,
            boolean commandExecutionAllowed
    ) {
        static TaskSourceContext empty() {
            return new TaskSourceContext("", "", "", false);
        }
    }

    public static final class Scope implements AutoCloseable {

        private boolean closed;

        @Override
        public void close() {
            if (closed) {
                return;
            }
            TASK_SOURCE_CONTEXT.remove();
            closed = true;
        }
    }
}
