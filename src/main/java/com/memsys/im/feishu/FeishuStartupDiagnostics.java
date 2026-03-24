package com.memsys.im.feishu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 飞书启动自检日志，便于快速定位“消息发得出但收不到”的接入问题。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnProperty(name = "im.feishu.enabled", havingValue = "true")
public class FeishuStartupDiagnostics implements ApplicationRunner {

    private final boolean feishuEnabled;
    private final boolean longConnectionEnabled;
    private final boolean webhookEnabled;
    private final String appId;
    private final String appSecret;
    private final String webhookUrl;
    private final String verificationToken;

    public FeishuStartupDiagnostics(
            @Value("${im.feishu.enabled:false}") boolean feishuEnabled,
            @Value("${im.feishu.long-connection-enabled:true}") boolean longConnectionEnabled,
            @Value("${im.feishu.webhook-enabled:false}") boolean webhookEnabled,
            @Value("${im.feishu.app-id:}") String appId,
            @Value("${im.feishu.app-secret:}") String appSecret,
            @Value("${im.feishu.webhook-url:}") String webhookUrl,
            @Value("${im.feishu.verification-token:}") String verificationToken
    ) {
        this.feishuEnabled = feishuEnabled;
        this.longConnectionEnabled = longConnectionEnabled;
        this.webhookEnabled = webhookEnabled;
        this.appId = normalize(appId);
        this.appSecret = normalize(appSecret);
        this.webhookUrl = normalize(webhookUrl);
        this.verificationToken = normalize(verificationToken);
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("FEISHU-CHECK config. enabled={}, longConnectionEnabled={}, webhookEnabled={}",
                feishuEnabled, longConnectionEnabled, webhookEnabled);
        log.info("FEISHU-CHECK credential. appId={}, appSecretSet={}",
                maskAppId(appId), !appSecret.isBlank());
        log.info("FEISHU-CHECK webhook. webhookUrlSet={}, verificationTokenSet={}",
                !webhookUrl.isBlank(), !verificationToken.isBlank());

        if (!longConnectionEnabled && !webhookEnabled) {
            log.warn("FEISHU-CHECK no inbound channel enabled. Enable long connection or webhook.");
        }
        if (longConnectionEnabled && webhookEnabled) {
            log.warn("FEISHU-CHECK both long connection and webhook are enabled. This can cause confusion when troubleshooting.");
        }
        if (appId.isBlank() || appSecret.isBlank()) {
            log.warn("FEISHU-CHECK app-id/app-secret missing. Inbound and outbound will not work.");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String maskAppId(String value) {
        if (value.isBlank()) {
            return "";
        }
        if (value.length() <= 6) {
            return "***";
        }
        return value.substring(0, 6) + "***";
    }
}
