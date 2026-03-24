package com.memsys.identity.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一用户身份模型 — Phase 9 #2。
 * <p>
 * 将不同平台（CLI/飞书/Telegram）的用户 ID 映射到同一个统一身份，
 * 使跨平台共享用户画像和任务成为可能。
 * <p>
 * 存储格式（identity_mappings.json）：
 * <pre>
 * {
 *   "user_001": {
 *     "unified_id": "user_001",
 *     "display_name": "Leo",
 *     "platform_bindings": {
 *       "cli": "default",
 *       "feishu": "ou_abc123",
 *       "telegram": "12345678"
 *     },
 *     "created_at": "2026-03-24T03:30:00"
 *   }
 * }
 * </pre>
 */
@Data
public class UserIdentity {

    /** 统一身份 ID，全局唯一 */
    private String unifiedId;

    /** 显示名称 */
    private String displayName;

    /**
     * 平台绑定映射：platform -> platform_user_id。
     * 例如 {"cli": "default", "feishu": "ou_abc123", "telegram": "12345678"}
     */
    private Map<String, String> platformBindings = new LinkedHashMap<>();

    /** 创建时间 */
    private LocalDateTime createdAt;

    public UserIdentity() {
    }

    public UserIdentity(String unifiedId, String displayName) {
        this.unifiedId = unifiedId;
        this.displayName = displayName;
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 为指定平台绑定用户 ID。
     */
    public void bindPlatform(String platform, String platformUserId) {
        this.platformBindings.put(platform.toLowerCase(), platformUserId);
    }

    /**
     * 获取指定平台的绑定 ID。
     */
    public String getPlatformUserId(String platform) {
        return this.platformBindings.get(platform.toLowerCase());
    }

    /**
     * 检查是否已绑定指定平台。
     */
    public boolean hasPlatformBinding(String platform) {
        return this.platformBindings.containsKey(platform.toLowerCase());
    }
}
