package com.memsys.cli;

import java.util.Map;

/**
 * 对话主链路过程事件，用于 CLI/IM/SSE 统一过程展示。
 */
public record ConversationProgressEvent(
        String stage,
        String message,
        Map<String, Object> details,
        long timestampMs
) {
}
