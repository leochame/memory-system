package com.memsys.memory;

import com.memsys.memory.model.Memory;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.rag.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 统一的记忆写入服务：封装 Memory 构造 → 持久化 → 队列更新 → RAG 索引。
 * 消除 ConversationCli、NightlyMemoryExtractionJob 中的重复模板代码。
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
     * 统一的记忆创建/覆写入口。
     */
    public void saveMemory(String slotName, String content,
                           Memory.MemoryType type, Memory.SourceType source,
                           String confidence) {
        Memory memory = new Memory();
        memory.setContent(content);
        memory.setMemoryType(type);
        memory.setSource(source);
        memory.setHitCount(0);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setLastAccessed(LocalDateTime.now());
        memory.setConfidence(confidence);

        // 持久化
        storage.writeUserInsight(slotName, memory);

        // 更新 Top of Mind 队列
        memoryManager.updateAccessTime(slotName);

        // RAG 索引
        if (ragEnabled) {
            try {
                ragService.indexMemory(slotName, memory);
            } catch (Exception e) {
                log.warn("Failed to index memory to vector store: {}", slotName, e);
            }
        }

        log.debug("Saved memory: {} [{}] -> {}", slotName, type, content);
    }

    /**
     * 便捷方法：不带 confidence 的记忆写入。
     */
    public void saveMemory(String slotName, String content,
                           Memory.MemoryType type, Memory.SourceType source) {
        saveMemory(slotName, content, type, source, null);
    }
}
