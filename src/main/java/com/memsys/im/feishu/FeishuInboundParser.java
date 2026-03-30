package com.memsys.im.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memsys.im.model.IncomingImMessage;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 飞书事件回调 -> 统一入站消息。
 */
@Component
public class FeishuInboundParser {

    private final ObjectMapper objectMapper;

    public FeishuInboundParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<IncomingImMessage> parseMessageEvent(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> header = asMap(payload.get("header"));
        String eventType = trim(header.get("event_type"));
        if (!"im.message.receive_v1".equals(eventType)) {
            return Optional.empty();
        }

        Map<String, Object> event = asMap(payload.get("event"));
        Map<String, Object> message = asMap(event.get("message"));
        if (message.isEmpty()) {
            return Optional.empty();
        }

        String messageType = trim(message.get("message_type"));
        if (!"text".equals(messageType) && !"post".equals(messageType)) {
            return Optional.empty();
        }

        String chatId = trim(message.get("chat_id"));
        if (chatId.isBlank()) {
            return Optional.empty();
        }

        String text = extractText(message.get("content"), messageType);
        if (text.isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> sender = asMap(event.get("sender"));
        Map<String, Object> senderId = asMap(sender.get("sender_id"));
        String userId = trim(senderId.get("open_id"));
        if (userId.isBlank()) {
            userId = trim(senderId.get("user_id"));
        }
        if (userId.isBlank()) {
            userId = trim(senderId.get("union_id"));
        }
        if (userId.isBlank()) {
            return Optional.empty();
        }

        long timestampMs = parseTimestampMs(message.get("create_time"));
        String messageId = trim(message.get("message_id"));

        return Optional.of(new IncomingImMessage(
                "feishu",
                chatId,
                userId,
                text,
                messageId,
                timestampMs
        ));
    }

    /**
     * 兼容飞书旧版消息事件（P1MessageReceivedV1）。
     */
    public Optional<IncomingImMessage> parseLegacyMessageEvent(Map<String, Object> event) {
        if (event == null || event.isEmpty()) {
            return Optional.empty();
        }
        String messageType = getFirstNonBlank(event, "msg_type", "msgType");
        if (!"text".equals(messageType) && !"post".equals(messageType)) {
            return Optional.empty();
        }

        String chatId = getFirstNonBlank(event, "open_chat_id", "openChatId");
        if (chatId.isBlank()) {
            return Optional.empty();
        }

        String textWithoutAtBot = getFirstNonBlank(event, "text_without_at_bot", "textWithoutAtBot");
        String text = textWithoutAtBot.isBlank() ? getFirstNonBlank(event, "text") : textWithoutAtBot;
        if (text.isBlank()) {
            return Optional.empty();
        }

        String userId = getFirstNonBlank(event, "open_id", "openId");
        if (userId.isBlank()) {
            userId = getFirstNonBlank(event, "employee_id", "employeeId");
        }
        if (userId.isBlank()) {
            userId = getFirstNonBlank(event, "union_id", "unionId");
        }
        if (userId.isBlank()) {
            return Optional.empty();
        }

        String messageId = getFirstNonBlank(event, "open_message_id", "openMessageId");
        long timestampMs = System.currentTimeMillis();

        return Optional.of(new IncomingImMessage(
                "feishu",
                chatId,
                userId,
                text.trim(),
                messageId,
                timestampMs
        ));
    }

    private String extractText(Object contentRaw, String messageType) {
        String raw = trim(contentRaw);
        if (raw.isBlank()) {
            return "";
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if ("post".equals(messageType)) {
                return extractPostText(node);
            }
            String text = node.path("text").asText("");
            return text == null ? "" : text.trim();
        } catch (Exception ignore) {
            return raw;
        }
    }

    private String extractPostText(JsonNode root) {
        JsonNode lines = extractPostContentLines(root);
        if (!lines.isArray()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode line : lines) {
            if (!line.isArray()) {
                continue;
            }
            for (JsonNode segment : line) {
                String text = segment.path("text").asText("");
                if (!text.isBlank()) {
                    builder.append(text);
                    continue;
                }
                String userName = segment.path("user_name").asText("");
                if (!userName.isBlank()) {
                    builder.append(userName);
                }
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
        }
        return builder.toString().trim();
    }

    private JsonNode extractPostContentLines(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return null;
        }
        JsonNode zhCn = root.path("zh_cn").path("content");
        if (zhCn.isArray()) {
            return zhCn;
        }
        var fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode content = entry.getValue() == null ? null : entry.getValue().path("content");
            if (content != null && content.isArray()) {
                return content;
            }
        }
        return null;
    }

    private long parseTimestampMs(Object raw) {
        long value = parseLong(raw);
        if (value <= 0) {
            return System.currentTimeMillis();
        }
        // 飞书事件里 create_time 常见为秒时间戳；如果是毫秒（13 位）则直接返回。
        if (value < 100_000_000_000L) {
            return value * 1000L;
        }
        return value;
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

    private String getFirstNonBlank(Map<String, Object> map, String... keys) {
        if (map == null || map.isEmpty() || keys == null || keys.length == 0) {
            return "";
        }
        for (String key : keys) {
            String value = trim(map.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
