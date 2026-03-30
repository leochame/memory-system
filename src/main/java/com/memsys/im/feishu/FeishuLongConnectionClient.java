package com.memsys.im.feishu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P1MessageReceivedV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import com.memsys.im.ImRuntimeService;
import com.memsys.im.model.IncomingImMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 飞书长连接客户端：
 * 无需公网 webhook URL，启动后通过官方 SDK 主动连接飞书并接收事件。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "im.feishu", name = {"enabled", "long-connection-enabled"}, havingValue = "true")
public class FeishuLongConnectionClient implements ApplicationRunner {
    private static final long DEDUP_WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final int DEDUP_MAX_SIZE = 10_000;
    private static final ConcurrentHashMap<String, Long> RECENT_MESSAGE_KEYS = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper;
    private final FeishuInboundParser inboundParser;
    private final ImRuntimeService imRuntimeService;
    private final String appId;
    private final String appSecret;
    private final ExecutorService eventExecutor;

    private Client wsClient;

    public FeishuLongConnectionClient(
            ObjectMapper objectMapper,
            FeishuInboundParser inboundParser,
            ImRuntimeService imRuntimeService,
            @Value("${im.feishu.app-id:}") String appId,
            @Value("${im.feishu.app-secret:}") String appSecret
    ) {
        this.objectMapper = objectMapper;
        this.inboundParser = inboundParser;
        this.imRuntimeService = imRuntimeService;
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.eventExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Feishu long connection init. appId={}, appSecretSet={}",
                maskAppId(appId), !appSecret.isBlank());
        if (appId.isBlank() || appSecret.isBlank()) {
            log.warn("Feishu long connection skipped: app-id/app-secret is blank.");
            return;
        }

        EventDispatcher eventDispatcher = EventDispatcher.newBuilder("", "")
                .onP1MessageReceivedV1(new ImService.P1MessageReceivedV1Handler() {
                    @Override
                    public void handle(P1MessageReceivedV1 event) {
                        eventExecutor.submit(() -> handleLegacyMessageEvent(event));
                    }
                })
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        eventExecutor.submit(() -> handleMessageEvent(event));
                    }
                })
                .build();

        try {
            wsClient = new Client.Builder(appId, appSecret)
                    .eventHandler(eventDispatcher)
                    .build();
            wsClient.start();
            log.info("Feishu long connection started.");
        } catch (Exception e) {
            log.error("Feishu long connection start failed. appId={}", maskAppId(appId), e);
        }
    }

    private void handleMessageEvent(P2MessageReceiveV1 event) {
        try {
            if (event == null || event.getEvent() == null) {
                return;
            }

            String eventJson = Jsons.DEFAULT.toJson(event.getEvent());
            Map<String, Object> eventBody = objectMapper.readValue(eventJson, new TypeReference<Map<String, Object>>() {
            });
            Map<String, Object> message = asMap(eventBody.get("message"));
            String messageId = trim(message.get("message_id"));
            String messageType = trim(message.get("message_type"));
            String chatType = trim(message.get("chat_type"));
            String chatId = trim(message.get("chat_id"));
            log.info("Feishu inbound received. messageId={}, chatType={}, messageType={}, chatId={}",
                    messageId, chatType, messageType, chatId);

            if (isBotSender(eventBody)) {
                log.info("Feishu inbound ignored (bot/app sender). messageId={}", messageId);
                return;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("header", Map.of("event_type", "im.message.receive_v1"));
            payload.put("event", eventBody);

            Optional<IncomingImMessage> incoming = inboundParser.parseMessageEvent(payload);
            if (incoming.isEmpty()) {
                log.info("Feishu inbound ignored by parser. messageId={}, messageType={}", messageId, messageType);
                return;
            }
            IncomingImMessage in = incoming.get();
            if (!shouldProcess(in)) {
                log.info("Feishu inbound ignored as duplicate. messageId={}, conversationId={}",
                        in.messageId(), in.conversationId());
                return;
            }
            try {
                String reply = imRuntimeService.handleIncomingAndReply(in);
                log.info("Feishu inbound handled. messageId={}, conversationId={}, replyLen={}",
                        messageId, in.conversationId(), reply == null ? 0 : reply.length());
            } catch (Exception processError) {
                log.warn("Feishu inbound processing failed. messageId={}, conversationId={}",
                        messageId, in.conversationId(), processError);
                try {
                    imRuntimeService.sendText("feishu", in.conversationId(), "收到消息了，但处理失败了，请稍后再试。");
                } catch (Exception sendFallbackError) {
                    log.warn("Feishu fallback reply failed. messageId={}, conversationId={}",
                            messageId, in.conversationId(), sendFallbackError);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to process Feishu long-connection message event", e);
        }
    }

    private void handleLegacyMessageEvent(P1MessageReceivedV1 event) {
        try {
            if (event == null || event.getEvent() == null) {
                return;
            }

            String eventJson = Jsons.DEFAULT.toJson(event.getEvent());
            Map<String, Object> eventBody = objectMapper.readValue(eventJson, new TypeReference<Map<String, Object>>() {
            });
            String messageId = trim(eventBody.get("open_message_id"));
            String messageType = trimFirstNonBlank(eventBody, "msg_type", "msgType");
            String chatType = trimFirstNonBlank(eventBody, "chat_type", "chatType");
            String chatId = trimFirstNonBlank(eventBody, "open_chat_id", "openChatId");
            log.info("Feishu legacy inbound received. messageId={}, chatType={}, messageType={}, chatId={}",
                    messageId, chatType, messageType, chatId);

            Optional<IncomingImMessage> incoming = inboundParser.parseLegacyMessageEvent(eventBody);
            if (incoming.isEmpty()) {
                log.info("Feishu legacy inbound ignored by parser. messageId={}, messageType={}",
                        messageId, messageType);
                return;
            }
            IncomingImMessage in = incoming.get();
            if (!shouldProcess(in)) {
                log.info("Feishu legacy inbound ignored as duplicate. messageId={}, conversationId={}",
                        in.messageId(), in.conversationId());
                return;
            }
            try {
                String reply = imRuntimeService.handleIncomingAndReply(in);
                log.info("Feishu legacy inbound handled. messageId={}, conversationId={}, replyLen={}",
                        messageId, in.conversationId(), reply == null ? 0 : reply.length());
            } catch (Exception processError) {
                log.warn("Feishu legacy inbound processing failed. messageId={}, conversationId={}",
                        messageId, in.conversationId(), processError);
                try {
                    imRuntimeService.sendText("feishu", in.conversationId(), "收到消息了，但处理失败了，请稍后再试。");
                } catch (Exception sendFallbackError) {
                    log.warn("Feishu legacy fallback reply failed. messageId={}, conversationId={}",
                            messageId, in.conversationId(), sendFallbackError);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to process Feishu long-connection legacy message event", e);
        }
    }

    private boolean isBotSender(Map<String, Object> eventBody) {
        Map<String, Object> sender = asMap(eventBody.get("sender"));
        String senderType = trim(sender.get("sender_type"));
        return "app".equalsIgnoreCase(senderType) || "bot".equalsIgnoreCase(senderType);
    }

    private Map<String, Object> asMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        map.forEach((k, v) -> converted.put(String.valueOf(k), v));
        return converted;
    }

    private String trim(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String trimFirstNonBlank(Map<String, Object> map, String key1, String key2) {
        String v1 = trim(map.get(key1));
        if (!v1.isBlank()) {
            return v1;
        }
        return trim(map.get(key2));
    }

    private String maskAppId(String value) {
        String raw = trim(value);
        if (raw.isBlank()) {
            return "";
        }
        if (raw.length() <= 6) {
            return "***";
        }
        return raw.substring(0, 6) + "***";
    }

    private boolean shouldProcess(IncomingImMessage incoming) {
        String messageId = incoming == null ? "" : trim(incoming.messageId());
        if (messageId.isBlank()) {
            return true;
        }
        cleanupDedupCache(System.currentTimeMillis());
        String key = "feishu:long-conn:" + incoming.conversationId() + ":" + messageId;
        return RECENT_MESSAGE_KEYS.putIfAbsent(key, System.currentTimeMillis()) == null;
    }

    private void cleanupDedupCache(long now) {
        RECENT_MESSAGE_KEYS.entrySet().removeIf(entry -> now - entry.getValue() > DEDUP_WINDOW_MILLIS);
        if (RECENT_MESSAGE_KEYS.size() <= DEDUP_MAX_SIZE) {
            return;
        }
        RECENT_MESSAGE_KEYS.clear();
    }

    @PreDestroy
    public void shutdown() {
        eventExecutor.shutdownNow();
        if (wsClient == null) {
            return;
        }
        // SDK 版本间 stop() 可用性不完全一致，使用反射做兼容关闭。
        try {
            wsClient.getClass().getMethod("stop").invoke(wsClient);
        } catch (Exception ignored) {
            // no-op
        }
        log.info("Feishu long connection stopped.");
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "feishu-long-conn-handler");
            thread.setDaemon(true);
            return thread;
        }
    }
}
