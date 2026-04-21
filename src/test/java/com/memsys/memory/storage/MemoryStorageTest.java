package com.memsys.memory.storage;

import com.memsys.memory.model.Memory;
import com.memsys.memory.MemoryScopeContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
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

    @Test
    void textStorageShouldRoundTripMultilineAndPipeCharacters() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LocalDateTime timestamp = LocalDateTime.of(2026, 3, 25, 12, 0);
        String message = "第一行|带分隔符\n第二行";

        storage.updateRecentMessages(message, timestamp, 10);
        storage.appendToHistory("user", message, timestamp);

        List<Map<String, Object>> recent = storage.getRecentMessages(10);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0)).containsEntry("message", message);

        List<Map<String, Object>> history = storage.getHistory(null, null);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("message", message);
    }

    @Test
    void listApisShouldHandleNonPositiveLimitsSafely() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LocalDateTime timestamp = LocalDateTime.of(2026, 3, 25, 12, 0);

        storage.updateRecentMessages("msg", timestamp, 0);
        storage.appendToHistory("user", "hello", timestamp);

        assertThat(storage.getRecentMessages(0)).isEmpty();
        assertThat(storage.getRecentMessages(-1)).isEmpty();
        assertThat(storage.getRecentConversationTurns(0)).isEmpty();
        assertThat(storage.getRecentConversationTurns(-2)).isEmpty();
        assertThat(storage.getOlderUserMessages(2, 1)).isEmpty();
        assertThat(storage.getOlderUserMessages(0, 0)).isEmpty();
    }

    @Test
    void drainPendingTaskNotificationsShouldSkipMalformedLines() throws IOException {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.appendPendingTaskNotification(Map.of("task_id", "t1", "type", "scheduled_task_due"));
        Files.writeString(
                tempDir.resolve("pending_task_notifications.jsonl"),
                "{bad-json}\n",
                StandardOpenOption.APPEND
        );
        storage.appendPendingTaskNotification(Map.of("task_id", "t2", "type", "scheduled_task_due"));

        List<Map<String, Object>> drained = storage.drainPendingTaskNotifications();
        assertThat(drained).hasSize(2);
        assertThat(drained)
                .extracting(it -> it.get("task_id"))
                .containsExactly("t1", "t2");
        assertThat(storage.drainPendingTaskNotifications()).isEmpty();
    }

    @Test
    void getHistoryShouldSkipMalformedTimestampLines() throws IOException {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LocalDateTime timestamp = LocalDateTime.of(2026, 3, 25, 13, 0);
        storage.appendToHistory("user", "ok", timestamp);
        Files.writeString(
                tempDir.resolve("conversation_history.jsonl"),
                "bad-time|user|b64:b2s\n",
                StandardOpenOption.APPEND
        );

        List<Map<String, Object>> history = storage.getHistory(null, null);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("message", "ok");
    }

    @Test
    void readPendingExplicitMemoriesShouldSkipMalformedLines() throws IOException {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.appendPendingExplicitMemory(Map.of("slot", "a", "content", "one"));
        Files.writeString(
                tempDir.resolve("pending_explicit_memories.jsonl"),
                "{bad-json}\n",
                StandardOpenOption.APPEND
        );
        storage.appendPendingExplicitMemory(Map.of("slot", "b", "content", "two"));

        List<Map<String, Object>> pending = storage.readPendingExplicitMemories();
        assertThat(pending).hasSize(2);
        assertThat(pending)
                .extracting(it -> it.get("slot"))
                .containsExactly("a", "b");
    }

    @Test
    void removePendingExplicitMemoryShouldRewriteRemainingValidRecords() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.appendPendingExplicitMemory(Map.of("slot_name", "a", "new_content", "one"));
        storage.appendPendingExplicitMemory(Map.of("slot_name", "b", "new_content", "two"));
        storage.appendPendingExplicitMemory(Map.of("slot_name", "c", "new_content", "three"));

        assertThat(storage.removePendingExplicitMemory(1)).isTrue();
        assertThat(storage.readPendingExplicitMemories())
                .extracting(it -> it.get("slot_name"))
                .containsExactly("a", "c");
        assertThat(storage.removePendingExplicitMemory(5)).isFalse();
        assertThat(storage.removePendingExplicitMemory(-1)).isFalse();
    }

    @Test
    void getOlderUserMessagesShouldUseUserTurnsWhenHistoryIsNotPaired() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LocalDateTime base = LocalDateTime.of(2026, 3, 25, 14, 0);
        storage.appendToHistory("user", "u1", base.plusMinutes(1));
        storage.appendToHistory("assistant", "a1", base.plusMinutes(2));
        storage.appendToHistory("assistant", "a-orphan", base.plusMinutes(3));
        storage.appendToHistory("user", "u2", base.plusMinutes(4));
        storage.appendToHistory("assistant", "a2", base.plusMinutes(5));
        storage.appendToHistory("user", "u3", base.plusMinutes(6));

        List<Map<String, Object>> result = storage.getOlderUserMessages(1, 3);
        assertThat(result)
                .extracting(it -> it.get("message"))
                .containsExactly("u2", "u3");
    }

    @Test
    void getRecentConversationTurnsShouldUseUserTurnBoundaryWhenHistoryIsNotPaired() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LocalDateTime base = LocalDateTime.of(2026, 3, 25, 15, 0);
        storage.appendToHistory("user", "u1", base.plusMinutes(1));
        storage.appendToHistory("assistant", "a1", base.plusMinutes(2));
        storage.appendToHistory("user", "u2", base.plusMinutes(3));
        storage.appendToHistory("assistant", "a2", base.plusMinutes(4));
        storage.appendToHistory("assistant", "a-orphan", base.plusMinutes(5));
        storage.appendToHistory("user", "u3", base.plusMinutes(6));

        List<Map<String, Object>> result = storage.getRecentConversationTurns(2);
        assertThat(result)
                .extracting(it -> it.get("message"))
                .containsExactly("u2", "a2", "a-orphan", "u3");
    }

    @Test
    void scopedStorageShouldIsolatePersonalAndTeamData() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        LocalDateTime base = LocalDateTime.of(2026, 3, 25, 15, 0);

        storage.appendToHistory("user", "default-user", base);

        try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(MemoryScopeContext.teamScope("lab"))) {
            storage.appendToHistory("user", "team-user", base.plusMinutes(1));
            assertThat(storage.getHistory(null, null))
                    .extracting(it -> it.get("message"))
                    .containsExactly("team-user");
        }

        assertThat(storage.getHistory(null, null))
                .extracting(it -> it.get("message"))
                .containsExactly("default-user");
    }

    @Test
    void readMemoryEvidenceTracesShouldSkipMalformedLines() throws IOException {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.appendMemoryEvidenceTrace(Map.of("user_message", "u1", "memory_loaded", true));
        Files.writeString(
                tempDir.resolve("memory_evidence_traces.jsonl"),
                "{bad-json}\n",
                StandardOpenOption.APPEND
        );
        storage.appendMemoryEvidenceTrace(Map.of("user_message", "u2", "memory_loaded", false));

        List<Map<String, Object>> traces = storage.readMemoryEvidenceTraces(10);
        assertThat(traces).hasSize(2);
        assertThat(traces)
                .extracting(it -> it.get("user_message"))
                .containsExactly("u1", "u2");
    }

    @Test
    void summaryFilesShouldBeInitializedAndRoundTrip() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());

        assertThat(tempDir.resolve("session_summaries.jsonl")).exists();
        assertThat(tempDir.resolve("topic_summaries.jsonl")).exists();
        assertThat(tempDir.resolve("milestone_summaries.jsonl")).exists();
        assertThat(tempDir.resolve("benchmark_questions.txt")).exists();
        assertThat(tempDir.resolve("benchmark_reports.jsonl")).exists();

        storage.appendSessionSummary(Map.of("summary", "session-1", "from_turn", 1, "to_turn", 3));
        storage.appendTopicSummary(Map.of("summary", "topic-1", "topic_label", "记忆治理"));
        storage.appendMilestoneSummary(Map.of("summary", "milestone-1", "milestone_label", "Benchmark 基线"));
        storage.appendBenchmarkReport(Map.of("dataset_source", ".memory/benchmark_questions.txt", "total_questions", 5));

        assertThat(storage.readSessionSummaries(10))
                .extracting(it -> it.get("summary"))
                .containsExactly("session-1");
        assertThat(storage.readTopicSummaries(10))
                .extracting(it -> it.get("topic_label"))
                .containsExactly("记忆治理");
        assertThat(storage.readMilestoneSummaries(10))
                .extracting(it -> it.get("milestone_label"))
                .containsExactly("Benchmark 基线");
        assertThat(storage.readBenchmarkQuestions())
                .hasSizeGreaterThanOrEqualTo(5)
                .contains("你觉得我最近关注的事情有哪些？");
        assertThat(storage.readBenchmarkReports(10))
                .extracting(it -> it.get("dataset_source"))
                .containsExactly(".memory/benchmark_questions.txt");
    }
}
