package com.memsys.memory;

import com.memsys.im.ImRuntimeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 主动提醒定时任务 — Phase 9 #5。
 * <p>
 * 定期（默认每 4 小时）检查是否应基于用户画像和记忆生成主动提醒。
 * 提醒通过 CLI 日志记录，可通过 /proactive-reminders 查看历史。
 * 若 IM 已配置，也可推送到 IM 平台。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>频率控制由 ProactiveReminderService 内部保证</li>
 *   <li>生成失败不阻断其他定时任务</li>
 *   <li>默认启用，可通过配置关闭</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ProactiveReminderJob {

    private final ProactiveReminderService proactiveReminderService;
    private final boolean proactiveEnabled;

    public ProactiveReminderJob(
            ProactiveReminderService proactiveReminderService,
            @Value("${scheduling.proactive-reminder-enabled:true}") boolean proactiveEnabled
    ) {
        this.proactiveReminderService = proactiveReminderService;
        this.proactiveEnabled = proactiveEnabled;
    }

    /**
     * 每 4 小时执行一次主动提醒检查。
     * cron 表达式：每天 8:00, 12:00, 16:00, 20:00 执行。
     */
    @Scheduled(cron = "${scheduling.proactive-reminder-cron:0 0 8,12,16,20 * * ?}")
    public void checkAndGenerateReminder() {
        if (!proactiveEnabled) {
            return;
        }

        try {
            String reminder = proactiveReminderService.tryGenerateReminder();
            if (reminder != null) {
                log.info("[主动提醒] {}", reminder);
            }
        } catch (Exception e) {
            log.warn("Proactive reminder job failed", e);
        }
    }
}
