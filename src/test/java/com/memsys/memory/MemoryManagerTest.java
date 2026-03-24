package com.memsys.memory;

import com.memsys.memory.model.Memory;
import com.memsys.memory.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void updateAccessTimePromotesSlotFromYoungToMature() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeUserInsight("food_preference", memory("用户不爱吃鱼", LocalDateTime.now().minusDays(2)));

        MemoryManager manager = new MemoryManager(storage, 100, 30, 15);

        manager.updateAccessTime("food_preference");
        List<List<String>> firstQueues = storage.loadQueues();
        assertThat(firstQueues.get(0)).containsExactly("food_preference");
        assertThat(firstQueues.get(1)).isEmpty();

        manager.updateAccessTime("food_preference");
        List<List<String>> secondQueues = storage.loadQueues();
        assertThat(secondQueues.get(0)).isEmpty();
        assertThat(secondQueues.get(1)).containsExactly("food_preference");

        Memory updated = storage.readUserInsights().get("food_preference");
        assertThat(updated.getHitCount()).isEqualTo(2);
    }

    @Test
    void cleanupOldMemoriesRemovesExpiredSlotsAndQueueEntries() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeUserInsight("old_slot", memory("旧偏好", LocalDateTime.now().minusDays(45)));
        storage.writeUserInsight("active_slot", memory("新偏好", LocalDateTime.now().minusDays(3)));
        storage.saveQueues(List.of("old_slot"), List.of("active_slot"));

        MemoryManager manager = new MemoryManager(storage, 100, 30, 15);
        List<String> deleted = manager.cleanupOldMemories(100, 30);

        assertThat(deleted).contains("old_slot");
        assertThat(storage.readUserInsights()).doesNotContainKey("old_slot").containsKey("active_slot");

        List<List<String>> queues = storage.loadQueues();
        assertThat(queues.get(0)).doesNotContain("old_slot");
        assertThat(queues.get(1)).contains("active_slot");
    }

    private Memory memory(String content, LocalDateTime lastAccessed) {
        Memory memory = new Memory();
        memory.setContent(content);
        memory.setMemoryType(Memory.MemoryType.USER_INSIGHT);
        memory.setSource(Memory.SourceType.EXPLICIT);
        memory.setConfidence("medium");
        memory.setHitCount(0);
        memory.setCreatedAt(lastAccessed.minusHours(1));
        memory.setLastAccessed(lastAccessed);
        return memory;
    }
}
