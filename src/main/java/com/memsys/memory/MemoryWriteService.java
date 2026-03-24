package com.memsys.memory;

import com.memsys.memory.model.Memory;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.rag.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一的记忆写入服务：封装 Memory 构造 → 冲突检测 → 持久化 → 队列更新 → RAG 索引。
 * 消除 ConversationCli、NightlyMemoryExtractionJob 中的重复模板代码。
 * <p>
 * Phase 9 增强：新增记忆治理能力（status / verification），
 * 当写入的 slotName 已存在且内容不同时，标记为 CONFLICT 并写入 pending 队列。
 */
@Slf4j
@Service
public class MemoryWriteService {

    private final MemoryStorage storage;
    private final MemoryManager memoryManager;
    private final RagService ragService;
    private final boolean ragEnabled;

    public MemoryWriteService(
            MemoryStorage storage,
            MemoryManager memoryManager,
            RagService ragService,
            @Value("${rag.enabled:true}") boolean ragEnabled
    ) {
        this.storage = storage;
        this.memoryManager = memoryManager;
        this.ragService = ragService;
        this.ragEnabled = ragEnabled;
    }

    /**
     * 统一的记忆创建/覆写入口（向后兼容）。
     * 默认 status=ACTIVE，不做冲突检测。
     */
    public void saveMemory(String slotName, String content,
                           Memory.MemoryType type, Memory.SourceType source,
                           String confidence) {
        saveMemoryWithGovernance(slotName, content, type, source, confidence,
                Memory.MemoryStatus.ACTIVE, false);
    }

    /**
     * 便捷方法：不带 confidence 的记忆写入。
     */
    public void saveMemory(String slotName, String content,
                           Memory.MemoryType type, Memory.SourceType source) {
        saveMemory(slotName, content, type, source, null);
    }

    /**
     * 带治理能力的记忆写入入口 — Phase 9 核心方法。
     * <p>
     * 当 detectConflict=true 时，如果 slotName 已存在且内容不同，
     * 不直接覆盖，而是将新记忆标记为 CONFLICT 写入 pending 队列等待用户裁决。
     *
     * @param slotName        记忆槽位名
     * @param content         记忆内容
     * @param type            记忆类型
     * @param source          来源类型
     * @param confidence      置信度
     * @param status          治理状态
     * @param detectConflict  是否启用冲突检测
     */
    public void saveMemoryWithGovernance(String slotName, String content,
                                          Memory.MemoryType type, Memory.SourceType source,
                                          String confidence, Memory.MemoryStatus status,
                                          boolean detectConflict) {
        // 冲突检测：当 slotName 已存在且内容不同时，标记为 CONFLICT
        if (detectConflict) {
            Map<String, Memory> existing = storage.readUserInsights();
            Memory existingMemory = existing.get(slotName);
            if (existingMemory != null && !existingMemory.getContent().equals(content)) {
                log.info("Memory conflict detected for slot '{}': existing='{}' vs new='{}'",
                        slotName,
                        truncate(existingMemory.getContent(), 80),
                        truncate(content, 80));

                // 将冲突记忆写入 pending 队列，不覆盖已有记忆
                Map<String, Object> conflictRecord = new HashMap<>();
                conflictRecord.put("slot_name", slotName);
                conflictRecord.put("new_content", content);
                conflictRecord.put("existing_content", existingMemory.getContent());
                conflictRecord.put("source", source.name());
                conflictRecord.put("confidence", confidence);
                conflictRecord.put("status", Memory.MemoryStatus.CONFLICT.name());
                conflictRecord.put("detected_at", LocalDateTime.now().toString());
                storage.appendPendingExplicitMemory(conflictRecord);

                log.info("Conflict memory queued to pending for slot '{}'", slotName);
                return;
            }
        }

        // 构建 Memory 对象
        Memory memory = new Memory();
        memory.setContent(content);
        memory.setMemoryType(type);
        memory.setSource(source);
        memory.setHitCount(0);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setLastAccessed(LocalDateTime.now());
        memory.setConfidence(confidence);
        memory.setStatus(status != null ? status : Memory.MemoryStatus.ACTIVE);

        // 如果是显式来源或 ACTIVE 状态，设置验证信息
        if (source == Memory.SourceType.EXPLICIT) {
            memory.setVerifiedAt(LocalDateTime.now());
            memory.setVerifiedSource("user_confirmed");
        } else if (status == Memory.MemoryStatus.ACTIVE) {
            memory.setVerifiedAt(LocalDateTime.now());
            memory.setVerifiedSource("auto_merged");
        }

        // 持久化
        storage.writeUserInsight(slotName, memory);

        // 更新 Top of Mind 队列
        memoryManager.updateAccessTime(slotName);

        // RAG 索引（仅 ACTIVE 状态的记忆参与 RAG）
        if (ragEnabled && memory.getStatus() == Memory.MemoryStatus.ACTIVE) {
            try {
                ragService.indexMemory(slotName, memory);
            } catch (Exception e) {
                log.warn("Failed to index memory to vector store: {}", slotName, e);
            }
        }

        log.debug("Saved memory: {} [{}] status={} -> {}", slotName, type, memory.getStatus(), content);
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }
}
