package com.memsys.im;

import com.memsys.cli.ConversationCli;
import com.memsys.im.feishu.FeishuOutboundClient;
import com.memsys.im.model.IncomingImMessage;
import com.memsys.im.telegram.TelegramOutboundClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * IM 消息统一编排：
 * 1) 收到消息后执行现有对话主流程
 * 2) 将回复通过对应平台发回
 * 3) 提供主动发送能力
 */
@Slf4j
@Service
public class ImRuntimeService {

    private final ConversationCli conversationCli;
    private final ObjectProvider<TelegramOutboundClient> telegramOutboundClientProvider;
    private final ObjectProvider<FeishuOutboundClient> feishuOutboundClientProvider;

    public ImRuntimeService(
            ConversationCli conversationCli,
            ObjectProvider<TelegramOutboundClient> telegramOutboundClientProvider,
            ObjectProvider<FeishuOutboundClient> feishuOutboundClientProvider
    ) {
        this.conversationCli = conversationCli;
        this.telegramOutboundClientProvider = telegramOutboundClientProvider;
        this.feishuOutboundClientProvider = feishuOutboundClientProvider;
    }

    public String handleIncomingAndReply(IncomingImMessage incoming) {
        if (incoming == null || incoming.text().isBlank()) {
            return "";
        }
        String reply = conversationCli.processUserMessage(
                incoming.text(),
                incoming.platform(),
                incoming.conversationId(),
                incoming.senderId()
        );
        sendText(incoming.platform(), incoming.conversationId(), reply);
        return reply;
    }

    public void sendText(String platform, String conversationId, String text) {
        String normalizedPlatform = normalizePlatform(platform);
        String targetConversationId = normalizeValue(conversationId);
        String outboundText = normalizeValue(text);
        if (targetConversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (outboundText.isBlank()) {
            log.info("Skip outbound send because text is blank. platform={}, conversationId={}",
                    normalizedPlatform, targetConversationId);
            return;
        }

        switch (normalizedPlatform) {
            case "telegram" -> {
                TelegramOutboundClient client = telegramOutboundClientProvider.getIfAvailable();
                if (client == null) {
                    throw new IllegalStateException("Telegram 未启用，无法发送消息。请设置 im.telegram.enabled=true");
                }
                client.sendText(targetConversationId, outboundText);
            }
            case "feishu" -> {
                FeishuOutboundClient client = feishuOutboundClientProvider.getIfAvailable();
                if (client == null) {
                    throw new IllegalStateException("飞书未启用，无法发送消息。请设置 im.feishu.enabled=true");
                }
                client.sendText(targetConversationId, outboundText);
            }
            default -> throw new IllegalArgumentException("不支持的平台: " + normalizedPlatform);
        }
    }

    private String normalizePlatform(String value) {
        return normalizeValue(value).toLowerCase();
    }

    private String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }
}
