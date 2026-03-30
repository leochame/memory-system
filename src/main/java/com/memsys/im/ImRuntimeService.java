package com.memsys.im;

import com.memsys.cli.ConversationCli;
import com.memsys.cli.ConversationProgressEvent;
import com.memsys.cli.ConversationProgressListener;
import com.memsys.identity.UserIdentityService;
import com.memsys.im.feishu.FeishuOutboundClient;
import com.memsys.im.model.ImConversationResult;
import com.memsys.im.model.IncomingImMessage;
import com.memsys.im.telegram.TelegramOutboundClient;
import com.memsys.memory.MemoryScopeContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
    private final boolean temporaryConversationForIm;

    public ImRuntimeService(
            ConversationCli conversationCli,
            UserIdentityService userIdentityService,
            ObjectProvider<TelegramOutboundClient> telegramOutboundClientProvider,
            ObjectProvider<FeishuOutboundClient> feishuOutboundClientProvider,
            @Value("${im.temporary-conversation-enabled:false}") boolean temporaryConversationForIm
    ) {
        this.conversationCli = conversationCli;
        this.userIdentityService = userIdentityService;
        this.telegramOutboundClientProvider = telegramOutboundClientProvider;
        this.feishuOutboundClientProvider = feishuOutboundClientProvider;
        this.temporaryConversationForIm = temporaryConversationForIm;
    }

    public String handleIncomingAndReply(IncomingImMessage incoming) {
        if (incoming == null || incoming.text().isBlank()) {
            return "";
        }
        ImConversationResult result = processIncoming(incoming, null, null);
        sendText(incoming.platform(), incoming.conversationId(), result.reply());
        return result.reply();
    }

    public ImConversationResult processIncoming(IncomingImMessage incoming,
                                                ConversationProgressListener progressListener,
                                                Boolean temporaryOverride) {
        if (incoming == null || incoming.text().isBlank()) {
            return new ImConversationResult("", List.of(), 0L);
        }
        long startedAt = System.currentTimeMillis();
        List<ConversationProgressEvent> steps = new ArrayList<>();
        ConversationProgressListener mergedListener = event -> {
            if (event == null) {
                return;
            }
            steps.add(event);
            if (progressListener != null) {
                progressListener.onEvent(event);
            }
        };

        // Phase 9 #2: 统一身份解析 — 将平台+senderId 映射到统一身份
        String unifiedId = userIdentityService.resolveUnifiedId(
                incoming.platform(), incoming.senderId());
        log.debug("Identity resolved: {}:{} -> {}", incoming.platform(), incoming.senderId(), unifiedId);
        String taskSourceSenderId = unifiedId.isBlank() ? incoming.senderId() : unifiedId;
        userIdentityService.recordConversationChannel(unifiedId, incoming.platform(), incoming.conversationId());

        boolean temporaryConversation = temporaryOverride != null
                ? temporaryOverride
                : temporaryConversationForIm;
        String reply;
        try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(
                MemoryScopeContext.personalScope(unifiedId))) {
            reply = temporaryConversation
                    ? conversationCli.processUserMessageTemporary(
                    incoming.text(),
                    incoming.platform(),
                    incoming.conversationId(),
                    taskSourceSenderId,
                    mergedListener
            )
                    : conversationCli.processUserMessage(
                    incoming.text(),
                    incoming.platform(),
                    incoming.conversationId(),
                    taskSourceSenderId,
                    mergedListener
            );
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);
        return new ImConversationResult(reply, List.copyOf(steps), durationMs);
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
