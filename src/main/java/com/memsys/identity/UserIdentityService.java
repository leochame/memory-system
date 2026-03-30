package com.memsys.identity;

import com.memsys.identity.model.UserIdentity;
import com.memsys.memory.storage.MemoryStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 统一身份映射服务 — Phase 9 #2 核心组件。
 * <p>
 * 职责：
 * <ul>
 *   <li>维护 platform+senderId → unified_id 的双向映射</li>
 *   <li>首次出现的平台用户自动创建身份（auto-bind）</li>
 *   <li>支持手动绑定已有身份到新平台</li>
 *   <li>为上层提供"当前用户是谁"的解析能力</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>CLI 默认用户为 "default"，统一身份为 "user_default"</li>
 *   <li>同一 unified_id 下的多平台共享同一套记忆和任务</li>
 *   <li>映射关系持久化到 identity_mappings.json</li>
 * </ul>
 */
@Slf4j
@Service
public class UserIdentityService {

    private static final String CLI_PLATFORM = "cli";
    private static final String CLI_DEFAULT_SENDER = "default";
    private static final String DEFAULT_UNIFIED_ID = "user_default";

    private final MemoryStorage storage;

    /** 内存缓存：platform:senderId -> unifiedId（反向索引） */
    private final Map<String, String> reverseIndex = new HashMap<>();

    /** 内存缓存：unifiedId -> UserIdentity */
    private final Map<String, UserIdentity> identities = new LinkedHashMap<>();

    private volatile boolean loaded = false;

    public UserIdentityService(MemoryStorage storage) {
        this.storage = storage;
    }

    /**
     * 根据平台和发送者 ID 解析统一身份 ID。
     * 如果是首次出现的用户，自动创建并绑定。
     *
     * @param platform 来源平台（cli/feishu/telegram）
     * @param senderId 平台内用户 ID
     * @return 统一身份 ID
     */
    public synchronized String resolveUnifiedId(String platform, String senderId) {
        ensureLoaded();

        String normalizedPlatform = normalize(platform);
        String normalizedSenderId = normalize(senderId);

        // CLI 无 senderId 时使用默认值
        if (normalizedPlatform.isBlank() || CLI_PLATFORM.equals(normalizedPlatform)) {
            normalizedPlatform = CLI_PLATFORM;
            if (normalizedSenderId.isBlank()) {
                normalizedSenderId = CLI_DEFAULT_SENDER;
            }
        }

        String key = buildKey(normalizedPlatform, normalizedSenderId);
        String existingId = reverseIndex.get(key);
        if (existingId != null) {
            return existingId;
        }

        // 首次出现：自动创建身份
        return autoBindNewUser(normalizedPlatform, normalizedSenderId);
    }

    /**
     * 手动将已有平台身份绑定到指定统一 ID。
     * 用于跨平台身份合并。
     *
     * @param unifiedId 目标统一身份 ID
     * @param platform  要绑定的平台
     * @param senderId  要绑定的平台用户 ID
     * @return true 绑定成功，false 统一 ID 不存在
     */
    public synchronized boolean bindPlatformToIdentity(String unifiedId, String platform, String senderId) {
        ensureLoaded();

        UserIdentity identity = identities.get(unifiedId);
        if (identity == null) {
            log.warn("Cannot bind: unified_id '{}' does not exist", unifiedId);
            return false;
        }

        String normalizedPlatform = normalize(platform);
        String normalizedSenderId = normalize(senderId);
        if (normalizedPlatform.isBlank() || normalizedSenderId.isBlank()) {
            log.warn("Cannot bind platform identity with blank platform/senderId");
            return false;
        }
        String key = buildKey(normalizedPlatform, normalizedSenderId);

        // 检查是否已绑定到其他身份
        String existingBinding = reverseIndex.get(key);
        if (existingBinding != null && !existingBinding.equals(unifiedId)) {
            log.info("Rebinding {}:{} from '{}' to '{}'",
                    normalizedPlatform, normalizedSenderId, existingBinding, unifiedId);
            // 从旧身份中移除绑定
            UserIdentity oldIdentity = identities.get(existingBinding);
            if (oldIdentity != null) {
                safeBindings(oldIdentity).remove(normalizedPlatform);
            }
        }

        identity.bindPlatform(normalizedPlatform, normalizedSenderId);
        reverseIndex.put(key, unifiedId);
        persistMappings();

        log.info("Bound {}:{} to unified_id '{}'", normalizedPlatform, normalizedSenderId, unifiedId);
        return true;
    }

    /**
     * 获取所有已知身份列表。
     */
    public synchronized List<UserIdentity> listAllIdentities() {
        ensureLoaded();
        return List.copyOf(identities.values());
    }

    /**
     * 根据统一 ID 获取身份。
     */
    public synchronized Optional<UserIdentity> getIdentity(String unifiedId) {
        ensureLoaded();
        return Optional.ofNullable(identities.get(unifiedId));
    }

    public synchronized void recordConversationChannel(String unifiedId, String platform, String conversationId) {
        ensureLoaded();
        String normalizedUnifiedId = normalize(unifiedId);
        String normalizedPlatform = normalize(platform);
        String normalizedConversationId = safeTrim(conversationId);
        if (normalizedUnifiedId.isBlank() || normalizedPlatform.isBlank() || normalizedConversationId.isBlank()) {
            return;
        }
        UserIdentity identity = identities.get(normalizedUnifiedId);
        if (identity == null) {
            return;
        }
        identity.bindConversation(normalizedPlatform, normalizedConversationId);
        persistMappings();
    }

    public synchronized String getConversationChannel(String unifiedId, String platform) {
        ensureLoaded();
        UserIdentity identity = identities.get(normalize(unifiedId));
        if (identity == null) {
            return "";
        }
        return safeTrim(identity.getConversationId(platform));
    }

    // ========== 内部方法 ==========

    private String autoBindNewUser(String platform, String senderId) {
        // CLI 默认用户使用固定 ID
        String unifiedId;
        if (CLI_PLATFORM.equals(platform) && CLI_DEFAULT_SENDER.equals(senderId)) {
            unifiedId = DEFAULT_UNIFIED_ID;
        } else {
            unifiedId = "user_" + platform + "_" + senderId;
        }

        // 如果统一 ID 已存在，直接添加绑定
        UserIdentity identity = identities.get(unifiedId);
        if (identity == null) {
            identity = new UserIdentity(unifiedId, senderId);
            identities.put(unifiedId, identity);
        }

        identity.bindPlatform(platform, senderId);
        reverseIndex.put(buildKey(platform, senderId), unifiedId);
        persistMappings();

        log.info("Auto-bound new user: {}:{} -> {}", platform, senderId, unifiedId);
        return unifiedId;
    }

    private void ensureLoaded() {
        if (!loaded) {
            loaded = loadMappings();
        }
    }

    private boolean loadMappings() {
        try {
            Map<String, UserIdentity> stored = storage.readIdentityMappingsOrThrow();
            identities.clear();
            reverseIndex.clear();
            identities.putAll(stored);

            // 重建反向索引
            for (Map.Entry<String, UserIdentity> entry : stored.entrySet()) {
                UserIdentity identity = entry.getValue();
                if (identity == null) {
                    continue;
                }
                for (Map.Entry<String, String> binding : safeBindings(identity).entrySet()) {
                    String key = buildKey(binding.getKey(), binding.getValue());
                    reverseIndex.put(key, identity.getUnifiedId());
                }
            }

            log.info("Loaded {} identity mappings with {} platform bindings",
                    identities.size(), reverseIndex.size());
            return true;
        } catch (Exception e) {
            log.warn("Failed to load identity mappings", e);
            return false;
        }
    }

    private void persistMappings() {
        storage.writeIdentityMappings(identities);
    }

    private String buildKey(String platform, String senderId) {
        return platform.toLowerCase() + ":" + senderId;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private Map<String, String> safeBindings(UserIdentity identity) {
        if (identity.getPlatformBindings() == null) {
            identity.setPlatformBindings(new LinkedHashMap<>());
        }
        return identity.getPlatformBindings();
    }
}
