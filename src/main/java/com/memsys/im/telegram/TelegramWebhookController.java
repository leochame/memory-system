package com.memsys.im.telegram;

import com.memsys.im.ImRuntimeService;
import com.memsys.im.model.IncomingImMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram webhook 入口。
 */
@Slf4j
@RestController
@ConditionalOnProperty(name = "im.telegram.enabled", havingValue = "true")
public class TelegramWebhookController {
    private static final long DEDUP_WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final int DEDUP_MAX_SIZE = 10_000;
    private static final ConcurrentHashMap<String, Long> RECENT_MESSAGE_KEYS = new ConcurrentHashMap<>();

    private final TelegramInboundParser inboundParser;
    private final ImRuntimeService imRuntimeService;
    private final String webhookSecret;

    public TelegramWebhookController(
            TelegramInboundParser inboundParser,
            ImRuntimeService imRuntimeService,
            @Value("${im.telegram.webhook-secret:}") String webhookSecret
    ) {
        this.inboundParser = inboundParser;
        this.imRuntimeService = imRuntimeService;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
    }

    @PostMapping("/webhooks/telegram")
    public ResponseEntity<Map<String, Object>> onUpdate(
            @RequestHeader(value = "X-Telegram-Bot-Api-Secret-Token", required = false) String requestSecret,
            @RequestBody Map<String, Object> body
    ) {
        if (!webhookSecret.isBlank() && !webhookSecret.equals(trim(requestSecret))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "ok", false,
                    "error", "invalid telegram webhook secret"
            ));
        }

        try {
            Optional<IncomingImMessage> incoming = inboundParser.parse(body);
            if (incoming.isEmpty()) {
                return ResponseEntity.ok(Map.of("ok", true, "ignored", true));
            }

            IncomingImMessage in = incoming.get();
            if (!shouldProcess(in)) {
                return ResponseEntity.ok(Map.of("ok", true, "ignored", true, "duplicate", true));
            }

            String reply = imRuntimeService.handleIncomingAndReply(in);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("platform", "telegram");
            result.put("conversation_id", in.conversationId());
            result.put("reply_len", reply == null ? 0 : reply.length());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Telegram webhook 处理失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "ok", false,
                    "error", "telegram webhook processing failed"
            ));
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean shouldProcess(IncomingImMessage incoming) {
        String messageId = incoming == null ? "" : trim(incoming.messageId());
        if (messageId.isBlank()) {
            return true;
        }
        cleanupDedupCache(System.currentTimeMillis());
        String key = "telegram:" + incoming.conversationId() + ":" + messageId;
        return RECENT_MESSAGE_KEYS.putIfAbsent(key, System.currentTimeMillis()) == null;
    }

    private void cleanupDedupCache(long now) {
        RECENT_MESSAGE_KEYS.entrySet().removeIf(entry -> now - entry.getValue() > DEDUP_WINDOW_MILLIS);
        if (RECENT_MESSAGE_KEYS.size() <= DEDUP_MAX_SIZE) {
            return;
        }
        RECENT_MESSAGE_KEYS.clear();
    }
}
