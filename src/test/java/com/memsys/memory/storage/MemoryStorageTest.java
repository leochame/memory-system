package com.memsys.memory.storage;

import com.memsys.memory.model.Memory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyUserInsightsJsonToMarkdownOnStartup() throws IOException {
        Files.writeString(tempDir.resolve("user_insights.json"), """
                {
                  "food_preference": {
                    "content": "用户不爱吃鱼",
                    "memoryType": "USER_INSIGHT",
                    "source": "EXPLICIT",
                    "hitCount": 2,
                    "createdAt": "2026-03-18 10:00:00",
                    "lastAccessed": "2026-03-18 11:00:00",
                    "confidence": "high"
                  }
                }
                """);

        MemoryStorage storage = new MemoryStorage(tempDir.toString());

        Path markdown = tempDir.resolve("user-insights.md");
        Path backup = tempDir.resolve("user_insights.json.migrated.bak");

        assertThat(markdown).exists();
        assertThat(backup).exists();
        assertThat(tempDir.resolve("user_insights.json")).doesNotExist();
        assertThat(storage.readUserInsights())
                .containsKey("food_preference");
        assertThat(storage.readUserInsightsNarrative())
                .contains("用户当前的长期画像如下。")
                .contains("用户不爱吃鱼");
        assertThat(Files.readString(markdown))
                .contains("<!-- memsys:state")
                .contains("\"food_preference\"");
    }

    @Test
    void writeUserInsightRebuildsNarrativeAndEmbeddedState() throws IOException {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        Memory memory = new Memory();
        memory.setContent("用户偏好简洁直接");
        memory.setMemoryType(Memory.MemoryType.USER_INSIGHT);
        memory.setSource(Memory.SourceType.IMPLICIT);
        memory.setHitCount(1);
        memory.setCreatedAt(LocalDateTime.of(2026, 3, 18, 9, 0));
        memory.setLastAccessed(LocalDateTime.of(2026, 3, 18, 10, 0));
        memory.setConfidence("medium");

        storage.writeUserInsight("response_style", memory);

        Map<String, Memory> memories = storage.readUserInsights();
        assertThat(memories)
                .containsKey("response_style");
        assertThat(memories.get("response_style").getContent())
                .isEqualTo("用户偏好简洁直接");
        assertThat(storage.readUserInsightsNarrative())
                .contains("用户偏好简洁直接。")
                .doesNotContain("当前还没有形成稳定的长期用户画像。");
        assertThat(Files.readString(tempDir.resolve("user-insights.md")))
                .contains("\"response_style\"")
                .contains("用户偏好简洁直接");
    }
}
