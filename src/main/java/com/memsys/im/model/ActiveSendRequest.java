package com.memsys.im.model;

/**
 * 主动发送消息 API 请求体。
 */
public record ActiveSendRequest(
        String platform,
        String conversationId,
        String text
) {
}

