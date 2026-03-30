package com.memsys.memory;

import com.memsys.memory.model.Memory;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.rag.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MemoryManager {

    private final Deque<String> youngQueue = new ArrayDeque<>();
    private final Deque<String> matureQueue = new ArrayDeque<>();
    private final MemoryStorage storage;
    private final int maxSlots;
    private final int daysUnaccessed;
    private final int topOfMindLimit;
    private final RagService ragService;

    @Autowired
    public MemoryManager(
            MemoryStorage storage,
            @Value("${memory.max-slots:100}") int maxSlots,
            @Value("${memory.days-unaccessed:30}") int daysUnaccessed,
            @Value("${memory.top-of-mind-limit:15}") int topOfMindLimit,
            RagService ragService
    ) {
        this.storage = storage;
        this.maxSlots = maxSlots;
        this.daysUnaccessed = daysUnaccessed;
        this.topOfMindLimit = topOfMindLimit;
        this.ragService = ragService;
        loadQueuesFromStorage();
    }

    public MemoryManager(
            MemoryStorage storage,
            int maxSlots,
            int daysUnaccessed,
            int topOfMindLimit
    ) {
        this.storage = storage;
        this.maxSlots = maxSlots;
        this.daysUnaccessed = daysUnaccessed;
        this.topOfMindLimit = topOfMindLimit;
        this.ragService = null;
        loadQueuesFromStorage();
    }

    public synchronized void loadQueuesFromStorage() {
        List<List<String>> queues = storage.loadQueues();
        youngQueue.clear();
        matureQueue.clear();
        youngQueue.addAll(queues.get(0));
        matureQueue.addAll(queues.get(1));
        log.info("Loaded queues - young: {}, mature: {}", youngQueue.size(), matureQueue.size());
    }

    public synchronized void saveQueuesToStorage() {
        storage.saveQueues(new ArrayList<>(youngQueue), new ArrayList<>(matureQueue));
    }

    public synchronized List<Map.Entry<String, Memory>> getTopOfMindMemories(int limit) {
        List<Map.Entry<String, Memory>> result = new ArrayList<>();
        Map<String, Memory> allMemories = getAllMemories();

        // 优先从 mature 队列选取
        for (String slotName : matureQueue) {
            if (result.size() >= limit) break;
            Memory memory = allMemories.get(slotName);
            if (memory != null) {
                result.add(Map.entry(slotName, memory));
            }
        }

        // 不足则从 young 队列补充
        for (String slotName : youngQueue) {
            if (result.size() >= limit) break;
            Memory memory = allMemories.get(slotName);
            if (memory != null && result.stream().noneMatch(e -> e.getKey().equals(slotName))) {
                result.add(Map.entry(slotName, memory));
            }
        }

        return result;
    }

    public synchronized void updateAccessTime(String slotName) {
        Memory memory = findMemory(slotName);
        if (memory == null) {
            log.warn("Memory not found: {}", slotName);
            return;
        }

        memory.setLastAccessed(LocalDateTime.now());
        memory.setHitCount(memory.getHitCount() + 1);

        // 更新队列位置
        if (youngQueue.contains(slotName)) {
            // 从 young 升级到 mature
            youngQueue.remove(slotName);
            matureQueue.addFirst(slotName);
        } else if (matureQueue.contains(slotName)) {
            // 在 mature 中移到头部
            matureQueue.remove(slotName);
            matureQueue.addFirst(slotName);
        } else {
            // 新记忆，加入 young 队列
            youngQueue.addFirst(slotName);
        }

        // 保存更新
        saveMemory(slotName, memory);
        saveQueuesToStorage();
    }

    public synchronized String detectConflict(String slotName, Map<String, Memory> existingMemories) {
        return existingMemories.containsKey(slotName) ? slotName : null;
    }

    public synchronized void overrideMemory(String slotName, String newContent, Map<String, Object> metadata) {
        Memory memory = findMemory(slotName);
        if (memory == null) {
            log.warn("Memory not found for override: {}", slotName);
            return;
        }

        memory.setContent(newContent);
        memory.setLastAccessed(LocalDateTime.now());
        memory.setHitCount(memory.getHitCount() + 1);

        if (metadata != null && metadata.containsKey("confidence")) {
            memory.setConfidence((String) metadata.get("confidence"));
        }

        saveMemory(slotName, memory);
    }

    public synchronized List<String> cleanupOldMemories(int maxSlots, int daysUnaccessed) {
        List<String> deleted = new ArrayList<>();
        Map<String, Memory> allMemories = getAllMemories();
        LocalDateTime cutoffDate = LocalDateTime.now().minus(daysUnaccessed, ChronoUnit.DAYS);

        // 删除长期未访问的记忆
        for (Map.Entry<String, Memory> entry : allMemories.entrySet()) {
            LocalDateTime lastAccessed = entry.getValue() == null ? null : entry.getValue().getLastAccessed();
            if (lastAccessed != null && lastAccessed.isBefore(cutoffDate)) {
                deleteMemory(entry.getKey());
                deleted.add(entry.getKey());
            }
        }

        // 如果超过槽位数限制，删除最旧的
        if (allMemories.size() - deleted.size() > maxSlots) {
            List<Map.Entry<String, Memory>> sorted = allMemories.entrySet().stream()
                .filter(e -> !deleted.contains(e.getKey()))
                .sorted(Comparator.comparing(
                        (Map.Entry<String, Memory> e) ->
                                e.getValue() == null ? null : e.getValue().getLastAccessed(),
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .collect(Collectors.toList());

            int toDelete = allMemories.size() - deleted.size() - maxSlots;
            for (int i = 0; i < toDelete && i < sorted.size(); i++) {
                String slotName = sorted.get(i).getKey();
                deleteMemory(slotName);
                deleted.add(slotName);
            }
        }

        // 清理队列
        youngQueue.removeAll(deleted);
        matureQueue.removeAll(deleted);
        saveQueuesToStorage();

        log.info("Cleaned up {} old memories", deleted.size());
        return deleted;
    }

    public synchronized Map<String, Memory> listAllMemories() {
        return getAllMemories();
    }

    public synchronized Memory getMemory(String slotName) {
        return findMemory(slotName);
    }

    public synchronized void editMemory(String slotName, String newContent) {
        Memory memory = findMemory(slotName);
        if (memory != null) {
            memory.setContent(newContent);
            saveMemory(slotName, memory);
        }
    }

    public synchronized void deleteMemory(String slotName) {
        Memory memory = findMemory(slotName);
        if (memory == null) return;

        storage.deleteUserInsight(slotName);
        if (ragService != null) {
            ragService.deleteMemory(slotName);
        }

        youngQueue.remove(slotName);
        matureQueue.remove(slotName);
        saveQueuesToStorage();
    }

    public synchronized void forgetMemory(String slotName, boolean keepHistory) {
        if (!keepHistory) {
            deleteMemory(slotName);
        }
    }

    public synchronized void setGlobalControl(boolean useSavedMemories, boolean useChatHistory) {
        Map<String, Object> metadata = storage.readMetadata();
        Map<String, Object> globalControls = new HashMap<>();
        globalControls.put("use_saved_memories", useSavedMemories);
        globalControls.put("use_chat_history", useChatHistory);
        metadata.put("global_controls", globalControls);
        storage.writeMetadata(metadata);
    }

    // 辅助方法

    private Map<String, Memory> getAllMemories() {
        return new HashMap<>(storage.readUserInsights());
    }

    private Memory findMemory(String slotName) {
        return storage.readUserInsights().get(slotName);
    }

    private void saveMemory(String slotName, Memory memory) {
        storage.writeUserInsight(slotName, memory);
    }
}
