package com.memsys.im.model;

/**
 * IM API 对话请求。
 */
public record ImChatRequest(
        String platform,
        String conversationId,
        String senderId,
        String text,
        Boolean temporary
) {
    public ImChatRequest {
        platform = trim(platform);
        conversationId = trim(conversationId);
        senderId = trim(senderId);
        text = trim(text);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
