package com.memsys.im.telegram;

import com.memsys.im.model.IncomingImMessage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Telegram Update -> 统一入站消息。
 */
@Component
public class TelegramInboundParser {

    public Optional<IncomingImMessage> parse(Map<String, Object> updateBody) {
        Map<String, Object> message = asMap(updateBody.get("message"));
        if (message.isEmpty()) {
            return Optional.empty();
        }

        String text = trim(message.get("text"));
        if (text.isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> chat = asMap(message.get("chat"));
        Map<String, Object> from = asMap(message.get("from"));

        String chatId = trim(chat.get("id"));
        if (chatId.isBlank()) {
            return Optional.empty();
        }

        String senderId = trim(from.get("id"));
        String messageId = trim(message.get("message_id"));
        long timestampMs = parseUnixSecondsToMillis(message.get("date"));

        return Optional.of(new IncomingImMessage(
                "telegram",
                chatId,
                senderId,
                text,
                messageId,
                timestampMs
        ));
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

    private long parseUnixSecondsToMillis(Object value) {
        long raw = parseLong(value);
        if (raw <= 0) {
            return System.currentTimeMillis();
        }
        return raw * 1000L;
    }

    private long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}

