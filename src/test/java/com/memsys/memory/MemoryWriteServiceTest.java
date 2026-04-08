package com.memsys.memory;

import com.memsys.memory.model.Memory;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.rag.RagService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MemoryWriteServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void approvePendingExplicitMemoryShouldPromoteRecordIntoActiveInsight() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryManager memoryManager = new MemoryManager(storage, 100, 30, 15);
        MemoryWriteService service = new MemoryWriteService(
                storage,
                memoryManager,
                mock(RagService.class),
                false
        );

        boolean approved = service.approvePendingExplicitMemory(Map.of(
                "slot_name", "food_preference",
                "new_content", "用户不爱吃鱼",
                "memory_type", "USER_INSIGHT",
                "source", "EXPLICIT",
                "confidence", "high"
        ));

        assertThat(approved).isTrue();
        Memory saved = storage.readUserInsights().get("food_preference");
        assertThat(saved).isNotNull();
        assertThat(saved.getContent()).isEqualTo("用户不爱吃鱼");
        assertThat(saved.getStatus()).isEqualTo(Memory.MemoryStatus.ACTIVE);
        assertThat(saved.getVerifiedAt()).isNotNull();
        assertThat(saved.getVerifiedSource()).isEqualTo("manual_cli_approval");
        assertThat(storage.readUserInsightsNarrative()).contains("用户不爱吃鱼");
    }

    @Test
    void approvePendingExplicitMemoryShouldRejectMalformedRecord() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        MemoryManager memoryManager = new MemoryManager(storage, 100, 30, 15);
        MemoryWriteService service = new MemoryWriteService(
                storage,
                memoryManager,
                mock(RagService.class),
                false
        );

        boolean approved = service.approvePendingExplicitMemory(Map.of(
                "slot_name", "food_preference"
        ));

        assertThat(approved).isFalse();
        assertThat(storage.readUserInsights()).isEmpty();
    }
}
