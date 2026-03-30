package com.memsys.im.feishu;

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
 * 飞书事件回调入口。
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "im.feishu", name = {"enabled", "webhook-enabled"}, havingValue = "true")
public class FeishuWebhookController {
    private static final long DEDUP_WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final int DEDUP_MAX_SIZE = 10_000;
    private static final ConcurrentHashMap<String, Long> RECENT_MESSAGE_KEYS = new ConcurrentHashMap<>();

    private final FeishuInboundParser inboundParser;
    private final ImRuntimeService imRuntimeService;
    private final String verificationToken;

    public FeishuWebhookController(
            FeishuInboundParser inboundParser,
            ImRuntimeService imRuntimeService,
            @Value("${im.feishu.verification-token:}") String verificationToken
    ) {
        this.inboundParser = inboundParser;
        this.imRuntimeService = imRuntimeService;
        this.verificationToken = verificationToken == null ? "" : verificationToken.trim();
    }

    @PostMapping("/webhooks/feishu/event")
    public ResponseEntity<Map<String, Object>> onEvent(@RequestBody Map<String, Object> payload) {
        try {
            if (!verificationToken.isBlank() && !verificationTokenMatched(payload)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                        "ok", false,
                        "error", "invalid feishu verification token"
                ));
            }
            if ("url_verification".equals(trim(payload.get("type")))) {
                return ResponseEntity.ok(Map.of("challenge", trim(payload.get("challenge"))));
            }

            Optional<IncomingImMessage> incoming = inboundParser.parseMessageEvent(payload);
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
            result.put("platform", "feishu");
            result.put("conversation_id", in.conversationId());
            result.put("reply_len", reply == null ? 0 : reply.length());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("飞书 webhook 处理失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "ok", false,
                    "error", "feishu webhook processing failed"
            ));
        }
    }

    private boolean verificationTokenMatched(Map<String, Object> payload) {
        String token = trim(payload.get("token"));
        Map<String, Object> header = asMap(payload.get("header"));
        if (verificationToken.equals(trim(header.get("token")))) {
            return true;
        }
        return verificationToken.equals(token);
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

    private boolean shouldProcess(IncomingImMessage incoming) {
        String messageId = incoming == null ? "" : trim(incoming.messageId());
        if (messageId.isBlank()) {
            return true;
        }
        cleanupDedupCache(System.currentTimeMillis());
        String key = "feishu:" + incoming.conversationId() + ":" + messageId;
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
