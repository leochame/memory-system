package com.memsys.im.model;

import com.memsys.cli.ConversationProgressEvent;

import java.util.List;

/**
 * IM 对话处理结果（最终回复 + 过程步骤 + 耗时）。
 */
public record ImConversationResult(
        String reply,
        List<ConversationProgressEvent> processSteps,
        long durationMs
) {
    public ImConversationResult {
        reply = reply == null ? "" : reply.trim();
        processSteps = processSteps == null ? List.of() : List.copyOf(processSteps);
        durationMs = Math.max(0L, durationMs);
    }
}
