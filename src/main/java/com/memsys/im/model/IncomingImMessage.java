package com.memsys.im.model;

/**
 * 统一的 IM 入站消息模型。
 */
public record IncomingImMessage(
        String platform,
        String conversationId,
        String senderId,
        String text,
        String messageId,
        long timestampMs
) {
    public IncomingImMessage {
        platform = trim(platform);
        conversationId = trim(conversationId);
        senderId = trim(senderId);
        text = trim(text);
        messageId = trim(messageId);
        if (timestampMs <= 0) {
            timestampMs = System.currentTimeMillis();
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

