package com.memsys.im;

import com.memsys.cli.ConversationCli;
import com.memsys.identity.UserIdentityService;
import com.memsys.im.feishu.FeishuOutboundClient;
import com.memsys.im.model.IncomingImMessage;
import com.memsys.im.telegram.TelegramOutboundClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * IM 消息统一编排：
 * 1) 收到消息后通过 UserIdentityService 解析统一身份
 * 2) 执行现有对话主流程
 * 3) 将回复通过对应平台发回
 * 4) 提供主动发送能力
 */
@Slf4j
@Service
public class ImRuntimeService {

    private final ConversationCli conversationCli;
    private final UserIdentityService userIdentityService;
    private final ObjectProvider<TelegramOutboundClient> telegramOutboundClientProvider;
    private final ObjectProvider<FeishuOutboundClient> feishuOutboundClientProvider;

    public ImRuntimeService(
            ConversationCli conversationCli,
            UserIdentityService userIdentityService,
            ObjectProvider<TelegramOutboundClient> telegramOutboundClientProvider,
            ObjectProvider<FeishuOutboundClient> feishuOutboundClientProvider
    ) {
        this.conversationCli = conversationCli;
        this.userIdentityService = userIdentityService;
        this.telegramOutboundClientProvider = telegramOutboundClientProvider;
        this.feishuOutboundClientProvider = feishuOutboundClientProvider;
    }

    public String handleIncomingAndReply(IncomingImMessage incoming) {
        if (incoming == null || incoming.text().isBlank()) {
            return "";
        }

        // Phase 9 #2: 统一身份解析 — 将平台+senderId 映射到统一身份
        String unifiedId = userIdentityService.resolveUnifiedId(
                incoming.platform(), incoming.senderId());
        log.debug("Identity resolved: {}:{} -> {}", incoming.platform(), incoming.senderId(), unifiedId);

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
