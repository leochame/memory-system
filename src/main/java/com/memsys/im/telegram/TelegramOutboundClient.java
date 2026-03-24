package com.memsys.im.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Telegram 文本消息发送。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "im.telegram.enabled", havingValue = "true")
public class TelegramOutboundClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String botToken;
    private final int sendTimeoutSeconds;

    public TelegramOutboundClient(
            ObjectMapper objectMapper,
            @Value("${im.telegram.base-url:https://api.telegram.org}") String baseUrl,
            @Value("${im.telegram.bot-token:}") String botToken,
            @Value("${im.telegram.send-timeout-seconds:8}") int sendTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.botToken = botToken == null ? "" : botToken.trim();
        this.sendTimeoutSeconds = Math.max(1, sendTimeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.sendTimeoutSeconds))
                .build();
    }

    public void sendText(String chatId, String text) {
        if (botToken.isBlank()) {
            throw new IllegalStateException("Telegram bot token 未配置（im.telegram.bot-token）");
        }
        String targetChat = chatId == null ? "" : chatId.trim();
        String outboundText = text == null ? "" : text.trim();
        if (targetChat.isBlank() || outboundText.isBlank()) {
            throw new IllegalArgumentException("Telegram sendText 参数不能为空");
        }

        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "chat_id", targetChat,
                    "text", outboundText
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/bot" + botToken + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(sendTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            validateResponse(response);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Telegram 发送消息失败", e);
        }
    }

    private void validateResponse(HttpResponse<String> response) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("Telegram HTTP 错误: status=" + response.statusCode() + ", body=" + response.body());
        }
        JsonNode body = objectMapper.readTree(response.body());
        if (!body.path("ok").asBoolean(false)) {
            throw new RuntimeException("Telegram API 返回失败: " + response.body());
        }
    }

    private String trimTrailingSlash(String raw) {
        if (raw == null || raw.isBlank()) {
            return "https://api.telegram.org";
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}

