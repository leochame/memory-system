package com.memsys.im.feishu;

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
import java.time.Instant;
import java.util.Map;

/**
 * 飞书文本消息发送（im.message.create）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "im.feishu.enabled", havingValue = "true")
public class FeishuOutboundClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String appId;
    private final String appSecret;
    private final int sendTimeoutSeconds;

    private volatile String cachedTenantToken = "";
    private volatile Instant cachedTenantTokenExpireAt = Instant.EPOCH;
    private final Object tokenLock = new Object();

    public FeishuOutboundClient(
            ObjectMapper objectMapper,
            @Value("${im.feishu.base-url:https://open.feishu.cn}") String baseUrl,
            @Value("${im.feishu.app-id:}") String appId,
            @Value("${im.feishu.app-secret:}") String appSecret,
            @Value("${im.feishu.send-timeout-seconds:8}") int sendTimeoutSeconds
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.appId = appId == null ? "" : appId.trim();
        this.appSecret = appSecret == null ? "" : appSecret.trim();
        this.sendTimeoutSeconds = Math.max(1, sendTimeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.sendTimeoutSeconds))
                .build();
    }

    public void sendText(String chatId, String text) {
        if (appId.isBlank() || appSecret.isBlank()) {
            throw new IllegalStateException("飞书 app-id/app-secret 未配置（im.feishu.app-id / im.feishu.app-secret）");
        }
        String targetChat = chatId == null ? "" : chatId.trim();
        String outboundText = text == null ? "" : text.trim();
        if (targetChat.isBlank() || outboundText.isBlank()) {
            throw new IllegalArgumentException("飞书 sendText 参数不能为空");
        }

        try {
            log.info("Feishu outbound send start. chatId={}, textLen={}", targetChat, outboundText.length());
            String tenantToken = getTenantAccessToken();
            String content = objectMapper.writeValueAsString(Map.of("text", outboundText));
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "receive_id", targetChat,
                    "msg_type", "text",
                    "content", content
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/open-apis/im/v1/messages?receive_id_type=chat_id"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + tenantToken)
                    .timeout(Duration.ofSeconds(sendTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = validateApiResponse("im.message.create", response);
            String messageId = body.path("data").path("message_id").asText("");
            log.info("Feishu outbound send success. chatId={}, messageId={}", targetChat, messageId);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Feishu outbound send failed. chatId={}", targetChat, e);
            throw new RuntimeException("飞书发送消息失败", e);
        }
    }

    private String getTenantAccessToken() throws IOException, InterruptedException {
        Instant now = Instant.now();
        if (!cachedTenantToken.isBlank() && now.isBefore(cachedTenantTokenExpireAt)) {
            return cachedTenantToken;
        }

        synchronized (tokenLock) {
            Instant checkNow = Instant.now();
            if (!cachedTenantToken.isBlank() && checkNow.isBefore(cachedTenantTokenExpireAt)) {
                return cachedTenantToken;
            }

            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "app_id", appId,
                    "app_secret", appSecret
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/open-apis/auth/v3/tenant_access_token/internal"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(sendTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode body = validateApiResponse("tenant_access_token/internal", response);

            String token = body.path("tenant_access_token").asText("");
            if (token.isBlank()) {
                throw new RuntimeException("飞书 tenant_access_token 为空: " + response.body());
            }
            long expireSeconds = body.path("expire").asLong(7200);
            long cacheSeconds = Math.max(60L, expireSeconds - 120L);

            cachedTenantToken = token;
            cachedTenantTokenExpireAt = Instant.now().plusSeconds(cacheSeconds);
            log.info("Feishu tenant token refreshed. expireSeconds={}", expireSeconds);
            return token;
        }
    }

    private JsonNode validateApiResponse(String apiName, HttpResponse<String> response) throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw new RuntimeException("飞书 " + apiName + " HTTP 错误: status="
                    + response.statusCode() + ", body=" + response.body());
        }

        JsonNode body = objectMapper.readTree(response.body());
        int code = body.path("code").asInt(-1);
        if (code != 0) {
            String msg = body.path("msg").asText("unknown");
            throw new RuntimeException("飞书 " + apiName + " 返回失败: code=" + code + ", msg=" + msg);
        }
        return body;
    }

    private String trimTrailingSlash(String raw) {
        if (raw == null || raw.isBlank()) {
            return "https://open.feishu.cn";
        }
        String trimmed = raw.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
