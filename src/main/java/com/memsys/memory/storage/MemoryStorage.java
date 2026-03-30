package com.memsys.memory.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.memsys.memory.MemoryScopeContext;
import com.memsys.memory.model.Memory;
import com.memsys.task.model.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MemoryStorage {

    private static final String LEGACY_USER_INSIGHTS_FILENAME = "user_insights.json";
    private static final String USER_INSIGHTS_MARKDOWN_FILENAME = "user-insights.md";
    private static final String USER_INSIGHTS_BACKUP_FILENAME = "user_insights.json.migrated.bak";
    private static final String USER_INSIGHTS_STATE_START = "<!-- memsys:state";
    private static final String USER_INSIGHTS_STATE_END = "-->";
    private static final String DEFAULT_USER_INSIGHTS_NARRATIVE = "当前还没有形成稳定的长期用户画像。";
    private static final String ENCODED_TEXT_PREFIX = "b64:";
    private static final Set<String> GLOBAL_FILES = Set.of(
            "identity_mappings.json"
    );

    private final Path basePath;
    private final ObjectMapper objectMapper;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MemoryStorage(@Value("${memory.base-path:.memory}") String basePathStr) {
        this.basePath = Paths.get(basePathStr);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        initializeStorage();
    }

    private void initializeStorage() {
        try {
            Files.createDirectories(basePath);
            Files.createDirectories(basePath.resolve("scopes"));

            // 迁移：旧文件 implicit_memories.json → user_insights.json
            Path oldImplicit = resolvePath("implicit_memories.json");
            Path newInsights = resolvePath(LEGACY_USER_INSIGHTS_FILENAME);
            if (Files.exists(oldImplicit) && !Files.exists(newInsights)) {
                Files.move(oldImplicit, newInsights);
                log.info("Migrated implicit_memories.json → user_insights.json");
            }

            initializeUserInsightsDocument();

            // 初始化文件
            createFileIfNotExists("metadata.json", "{}");
            createFileIfNotExists("recent_user_messages.jsonl", "");
            createFileIfNotExists("conversation_history.jsonl", "");
            createFileIfNotExists("memory_queues.json", "{\"young_queue\":[],\"mature_queue\":[]}");
            createFileIfNotExists("pending_explicit_memories.jsonl", "");
            createFileIfNotExists("scheduled_tasks.json", "[]");
            createFileIfNotExists("pending_task_notifications.jsonl", "");
            createFileIfNotExists("session_summaries.jsonl", "");
            createFileIfNotExists("memory_evidence_traces.jsonl", "");
            createFileIfNotExists("eval_results.jsonl", "");
            createFileIfNotExists("weekly_reviews.jsonl", "");
            createFileIfNotExists("identity_mappings.json", "{}");

            log.info("Memory storage initialized at: {}", basePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to initialize storage", e);
            throw new RuntimeException("Failed to initialize storage", e);
        }
    }

    private void createFileIfNotExists(String filename, String defaultContent) throws IOException {
        Path filePath = resolvePath(filename);
        ensureParentDirectory(filePath);
        if (!Files.exists(filePath)) {
            Files.writeString(filePath, defaultContent);
        }
    }

    // ========== metadata.json 操作 ==========

    public Map<String, Object> readMetadata() {
        try {
            String content = Files.readString(resolvePath("metadata.json"));
            return objectMapper.readValue(content, Map.class);
        } catch (IOException e) {
            log.error("Failed to read metadata", e);
            return new HashMap<>();
        }
    }

    public void writeMetadata(Map<String, Object> metadata) {
        writeJsonFile("metadata.json", metadata);
    }

    public void updateAssistantPreferences(Map<String, Object> preferences) {
        Map<String, Object> metadata = readMetadata();
        metadata.put("assistant_preferences", preferences);
        writeMetadata(metadata);
    }

    // ========== user_insights.json 操作 ==========

    public Map<String, Memory> readUserInsights() {
        return parseUserInsightsDocument(readUserInsightsDocument()).memories();
    }

    public void writeUserInsight(String slotName, Memory memory) {
        Map<String, Memory> memories = readUserInsights();
        memories.put(slotName, memory);
        writeUserInsights(memories);
    }

    public void deleteUserInsight(String slotName) {
        Map<String, Memory> memories = readUserInsights();
        memories.remove(slotName);
        writeUserInsights(memories);
    }

    public String readUserInsightsNarrative() {
        return parseUserInsightsDocument(readUserInsightsDocument()).narrative();
    }

    // ========== recent_user_messages.jsonl 操作 ==========

    public void updateRecentMessages(String message, LocalDateTime timestamp, int limit) {
        if (limit <= 0) {
            return;
        }
        try {
            Path filePath = resolvePath("recent_user_messages.jsonl");
            List<String> lines = Files.exists(filePath) ?
                Files.readAllLines(filePath) : new ArrayList<>();

            // 添加新消息
            String newLine = formatter.format(timestamp) + "|" + encodeText(message);
            lines.add(newLine);

            // 保持滚动窗口
            if (lines.size() > limit) {
                lines = lines.subList(lines.size() - limit, lines.size());
            }

            // 原子写入，避免并发读写导致文件被截断或写入不完整
            Path tempFile = newTempFile("recent_user_messages.jsonl");
            Files.writeString(tempFile, String.join("\n", lines) + "\n");
            Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to update recent messages", e);
        }
    }

    public List<Map<String, Object>> getRecentMessages(int limit) {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        try {
            Path filePath = resolvePath("recent_user_messages.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }

            List<String> lines = Files.readAllLines(filePath);
            // 获取最新的N条消息（从列表尾部开始）
            int startIndex = Math.max(0, lines.size() - limit);
            return lines.subList(startIndex, lines.size()).stream()
                .map(line -> {
                    String[] parts = line.split("\\|", 2);
                    Map<String, Object> msg = new HashMap<>();
                    if (parts.length == 2) {
                        msg.put("timestamp", parts[0]);
                        msg.put("message", decodeText(parts[1]));
                    }
                    return msg;
                })
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Failed to read recent messages", e);
            return new ArrayList<>();
        }
    }

    // ========== conversation_history.jsonl 操作 ==========

    public void appendToHistory(String role, String message, LocalDateTime timestamp) {
        try {
            Path filePath = resolvePath("conversation_history.jsonl");
            String line = formatter.format(timestamp) + "|" + role + "|" + encodeText(message) + "\n";
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append to history", e);
        }
    }

    // ========== pending_explicit_memories.jsonl 操作 ==========

    /**
     * 异步显式记忆提取的“待处理”记录（例如发生冲突时不覆盖，写入此 inbox 供后续人工处理）。
     * 采用 jsonl 追加写，避免全量读写。
     */
    public void appendPendingExplicitMemory(Map<String, Object> record) {
        try {
            Path filePath = resolvePath("pending_explicit_memories.jsonl");
            String line = objectMapper.writeValueAsString(record) + "\n";
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append pending explicit memory", e);
        }
    }

    /**
     * 读取所有待处理的显式记忆（包括冲突记忆）。
     * Phase 9 治理功能 — 支持 /memory-governance 命令展示。
     */
    public List<Map<String, Object>> readPendingExplicitMemories() {
        List<Map<String, Object>> results = new ArrayList<>();
        try {
            Path filePath = resolvePath("pending_explicit_memories.jsonl");
            if (!Files.exists(filePath)) {
                return results;
            }
            List<String> lines = Files.readAllLines(filePath);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> record = objectMapper.readValue(line, Map.class);
                    results.add(record);
                } catch (IOException parseErr) {
                    log.warn("Skipped malformed pending explicit memory line: {}", line);
                }
            }
        } catch (IOException e) {
            log.error("Failed to read pending explicit memories", e);
        }
        return results;
    }

    // ========== scheduled_tasks.json 操作 ==========

    public List<ScheduledTask> readScheduledTasks() {
        try {
            Path filePath = resolvePath("scheduled_tasks.json");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            String content = Files.readString(filePath);
            if (content == null || content.isBlank()) {
                return new ArrayList<>();
            }
            List<ScheduledTask> tasks = objectMapper.readValue(content, new TypeReference<List<ScheduledTask>>() {
            });
            return tasks == null ? new ArrayList<>() : tasks;
        } catch (IOException e) {
            log.error("Failed to read scheduled tasks", e);
            return new ArrayList<>();
        }
    }

    public void writeScheduledTasks(List<ScheduledTask> tasks) {
        writeJsonFile("scheduled_tasks.json", tasks == null ? new ArrayList<>() : tasks);
    }

    public void appendPendingTaskNotification(Map<String, Object> record) {
        try {
            Path filePath = resolvePath("pending_task_notifications.jsonl");
            String line = objectMapper.writeValueAsString(record) + "\n";
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append pending task notification", e);
        }
    }

    public List<Map<String, Object>> drainPendingTaskNotifications() {
        Path drainingPath = null;
        try {
            Path filePath = resolvePath("pending_task_notifications.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            drainingPath = newTempFile("pending_task_notifications.jsonl.drain");
            try {
                Files.move(filePath, drainingPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(filePath, drainingPath, StandardCopyOption.REPLACE_EXISTING);
            }

            List<String> lines = Files.readAllLines(drainingPath);
            List<Map<String, Object>> notifications = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    notifications.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {
                    }));
                } catch (IOException parseErr) {
                    log.warn("Skipped malformed pending task notification line: {}", line);
                }
            }
            return notifications;
        } catch (IOException e) {
            log.error("Failed to drain pending task notifications", e);
            return new ArrayList<>();
        } finally {
            if (drainingPath != null) {
                try {
                    Files.deleteIfExists(drainingPath);
                } catch (IOException cleanupErr) {
                    log.warn("Failed to cleanup drained pending task notification file: {}", drainingPath, cleanupErr);
                }
            }
        }
    }

    public List<Map<String, Object>> getHistory(LocalDateTime startDate, LocalDateTime endDate) {
        try {
            Path filePath = resolvePath("conversation_history.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> history = new ArrayList<>();
            List<String> lines = Files.readAllLines(filePath);
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|", 3);
                if (parts.length != 3) {
                    continue;
                }
                try {
                    LocalDateTime timestamp = LocalDateTime.parse(parts[0], formatter);
                    if ((startDate != null && timestamp.isBefore(startDate))
                            || (endDate != null && timestamp.isAfter(endDate))) {
                        continue;
                    }
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("timestamp", parts[0]);
                    entry.put("role", parts[1]);
                    entry.put("message", decodeText(parts[2]));
                    history.add(entry);
                } catch (DateTimeParseException ignored) {
                    log.warn("Skipped malformed history timestamp line: {}", line);
                }
            }
            return history;
        } catch (IOException e) {
            log.error("Failed to read history", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取最近 N 轮完整对话（用户+助手）
     * 一轮对话 = 一条用户消息 + 一条助手回复
     * 
     * @param turns 需要获取的对话轮数
     * @return 按时间顺序排列的对话消息列表，每条消息包含 timestamp, role, message
     */
    public List<Map<String, Object>> getRecentConversationTurns(int turns) {
        if (turns <= 0) {
            return new ArrayList<>();
        }
        try {
            Path filePath = resolvePath("conversation_history.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }

            List<String> lines = Files.readAllLines(filePath);
            if (lines.isEmpty()) {
                return new ArrayList<>();
            }

            // 从后往前读取，找到最近的 N 轮对话
            // 一轮对话 = 一条用户消息 + 一条助手回复（按时间顺序）
            List<Map<String, Object>> allMessages = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                String[] parts = line.split("\\|", 3);
                if (parts.length == 3) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("timestamp", parts[0]);
                    entry.put("role", parts[1]);
                    entry.put("message", decodeText(parts[2]));
                    allMessages.add(0, entry); // 插入到开头，保持时间顺序
                }
            }

            // 以“用户消息”定义轮次，返回最近 N 条用户消息开始到结尾的完整消息片段。
            List<Integer> userMessageIndexes = new ArrayList<>();
            for (int i = 0; i < allMessages.size(); i++) {
                if ("user".equals(allMessages.get(i).get("role"))) {
                    userMessageIndexes.add(i);
                }
            }
            if (userMessageIndexes.isEmpty()) {
                return new ArrayList<>();
            }

            int startUserPos = Math.max(0, userMessageIndexes.size() - turns);
            int startMessageIndex = userMessageIndexes.get(startUserPos);

            return new ArrayList<>(allMessages.subList(startMessageIndex, allMessages.size()));
        } catch (IOException e) {
            log.error("Failed to read recent conversation turns", e);
            return new ArrayList<>();
        }
    }

    /**
     * 获取第 (startTurn+1) 到 endTurn 轮的用户消息
     * 用于系统提示词中的"最近对话内容"模块
     * 
     * @param startTurn 起始轮数（不包含），例如 10 表示从第 11 轮开始
     * @param endTurn 结束轮数（包含），例如 40 表示到第 40 轮结束
     * @return 用户消息列表，每条消息包含 timestamp, message
     */
    public List<Map<String, Object>> getOlderUserMessages(int startTurn, int endTurn) {
        if (endTurn <= startTurn || endTurn <= 0) {
            return new ArrayList<>();
        }
        try {
            Path filePath = resolvePath("conversation_history.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }

            List<String> lines = Files.readAllLines(filePath);
            if (lines.isEmpty()) {
                return new ArrayList<>();
            }

            // 从后往前读取所有消息
            List<Map<String, Object>> allMessages = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0; i--) {
                String line = lines.get(i);
                String[] parts = line.split("\\|", 3);
                if (parts.length == 3) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("timestamp", parts[0]);
                    entry.put("role", parts[1]);
                    entry.put("message", decodeText(parts[2]));
                    allMessages.add(0, entry); // 插入到开头，保持时间顺序
                }
            }

            // 以“用户消息数”定义轮次，避免历史中出现不成对消息时索引偏移。
            List<Map<String, Object>> userOnlyMessages = allMessages.stream()
                    .filter(msg -> "user".equals(msg.get("role")))
                    .toList();

            if (userOnlyMessages.size() <= startTurn) {
                return new ArrayList<>();
            }

            List<Map<String, Object>> userMessages = new ArrayList<>();
            int startIndex = startTurn;
            int endExclusive = Math.min(userOnlyMessages.size(), endTurn);
            for (int i = startIndex; i < endExclusive; i++) {
                Map<String, Object> msg = userOnlyMessages.get(i);
                Map<String, Object> userMsg = new HashMap<>();
                userMsg.put("timestamp", msg.get("timestamp"));
                userMsg.put("message", msg.get("message"));
                userMessages.add(userMsg);
            }

            return userMessages;
        } catch (IOException e) {
            log.error("Failed to read older user messages", e);
            return new ArrayList<>();
        }
    }

    // ========== session_summaries.jsonl 操作 ==========

    /**
     * 追加一条会话摘要记录到 session_summaries.jsonl。
     * Phase 8 核心落盘方法。
     */
    public void appendSessionSummary(Map<String, Object> summaryRecord) {
        try {
            Path filePath = resolvePath("session_summaries.jsonl");
            String line = objectMapper.writeValueAsString(summaryRecord) + "\n";
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Session summary appended to session_summaries.jsonl");
        } catch (IOException e) {
            log.error("Failed to append session summary", e);
        }
    }

    /**
     * 读取所有会话摘要记录。
     *
     * @param limit 最多返回的记录数（0 表示不限）
     * @return 按时间顺序排列的摘要列表
     */
    public List<Map<String, Object>> readSessionSummaries(int limit) {
        try {
            Path filePath = resolvePath("session_summaries.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            List<String> lines = Files.readAllLines(filePath);
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                try {
                    summaries.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {}));
                } catch (IOException parseErr) {
                    log.warn("Skipped malformed session summary line: {}", line);
                }
            }
            if (limit > 0 && summaries.size() > limit) {
                return summaries.subList(summaries.size() - limit, summaries.size());
            }
            return summaries;
        } catch (IOException e) {
            log.error("Failed to read session summaries", e);
            return new ArrayList<>();
        }
    }

    // ========== memory_evidence_traces.jsonl 操作 ==========

    /**
     * 追加单条 Memory Evidence Trace（JSONL）。
     */
    public void appendMemoryEvidenceTrace(Map<String, Object> traceRecord) {
        try {
            Path filePath = resolvePath("memory_evidence_traces.jsonl");
            String line = objectMapper.writeValueAsString(traceRecord) + "\n";
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append memory evidence trace", e);
        }
    }

    /**
     * 读取 Memory Evidence Trace 历史（按时间顺序，limit>0 时返回最后 N 条）。
     */
    public List<Map<String, Object>> readMemoryEvidenceTraces(int limit) {
        try {
            Path filePath = resolvePath("memory_evidence_traces.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            List<String> lines = Files.readAllLines(filePath);
            List<Map<String, Object>> traces = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    traces.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {
                    }));
                } catch (IOException parseErr) {
                    log.warn("Skipped malformed memory evidence trace line: {}", line);
                }
            }
            if (limit > 0 && traces.size() > limit) {
                return traces.subList(traces.size() - limit, traces.size());
            }
            return traces;
        } catch (IOException e) {
            log.error("Failed to read memory evidence traces", e);
            return new ArrayList<>();
        }
    }

    // ========== eval_results.jsonl 操作 ==========

    /**
     * 追加单条评测结果。
     */
    public void appendEvalResult(Map<String, Object> evalRecord) {
        try {
            Path filePath = resolvePath("eval_results.jsonl");
            String line = objectMapper.writeValueAsString(evalRecord) + "\n";
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append eval result", e);
        }
    }

    /**
     * 读取评测结果（按时间顺序，limit>0 时返回最后 N 条）。
     */
    public List<Map<String, Object>> readEvalResults(int limit) {
        try {
            Path filePath = resolvePath("eval_results.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            List<String> lines = Files.readAllLines(filePath);
            List<Map<String, Object>> results = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    results.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {
                    }));
                } catch (IOException parseErr) {
                    log.warn("Skipped malformed eval result line: {}", line);
                }
            }
            if (limit > 0 && results.size() > limit) {
                return results.subList(results.size() - limit, results.size());
            }
            return results;
        } catch (IOException e) {
            log.error("Failed to read eval results", e);
            return new ArrayList<>();
        }
    }

    // ========== proactive_reminders.jsonl 操作 ==========

    /**
     * 追加一条主动提醒记录到 proactive_reminders.jsonl。
     * Phase 9 #5 — 基于记忆生成主动提醒。
     */
    public void appendProactiveReminder(Map<String, Object> reminderRecord) {
        try {
            Path filePath = resolvePath("proactive_reminders.jsonl");
            String line = objectMapper.writeValueAsString(reminderRecord) + "\n";
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Proactive reminder appended to proactive_reminders.jsonl");
        } catch (IOException e) {
            log.error("Failed to append proactive reminder", e);
        }
    }

    /**
     * 读取主动提醒记录。
     *
     * @param limit 最多返回的记录数（0 表示不限）
     * @return 提醒记录列表（按时间顺序）
     */
    public List<Map<String, Object>> readProactiveReminders(int limit) {
        try {
            Path filePath = resolvePath("proactive_reminders.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            List<String> lines = Files.readAllLines(filePath);
            List<Map<String, Object>> reminders = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                try {
                    reminders.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {}));
                } catch (IOException parseErr) {
                    log.warn("Skipped malformed proactive reminder line: {}", line);
                }
            }
            if (limit > 0 && reminders.size() > limit) {
                return reminders.subList(reminders.size() - limit, reminders.size());
            }
            return reminders;
        } catch (IOException e) {
            log.error("Failed to read proactive reminders", e);
            return new ArrayList<>();
        }
    }

    // ========== weekly_reviews.jsonl 操作 ==========

    public void appendWeeklyReview(Map<String, Object> reviewRecord) {
        try {
            Path filePath = resolvePath("weekly_reviews.jsonl");
            String line = objectMapper.writeValueAsString(reviewRecord) + "\n";
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append weekly review", e);
        }
    }

    public List<Map<String, Object>> readWeeklyReviews(int limit) {
        try {
            Path filePath = resolvePath("weekly_reviews.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            List<String> lines = Files.readAllLines(filePath);
            List<Map<String, Object>> reviews = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                try {
                    reviews.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {}));
                } catch (IOException parseErr) {
                    log.warn("Skipped malformed weekly review line: {}", line);
                }
            }
            if (limit > 0 && reviews.size() > limit) {
                return reviews.subList(reviews.size() - limit, reviews.size());
            }
            return reviews;
        } catch (IOException e) {
            log.error("Failed to read weekly reviews", e);
            return new ArrayList<>();
        }
    }

    // ========== memory_queues.json 操作 ==========

    public void saveQueues(List<String> youngQueue, List<String> matureQueue) {
        Map<String, List<String>> queues = new HashMap<>();
        queues.put("young_queue", youngQueue);
        queues.put("mature_queue", matureQueue);
        writeJsonFile("memory_queues.json", queues);
    }

    public List<List<String>> loadQueues() {
        try {
            String content = Files.readString(resolvePath("memory_queues.json"));
            Map<String, List<String>> queues = objectMapper.readValue(content, Map.class);
            return Arrays.asList(
                queues.getOrDefault("young_queue", new ArrayList<>()),
                queues.getOrDefault("mature_queue", new ArrayList<>())
            );
        } catch (IOException e) {
            log.error("Failed to load queues", e);
            return Arrays.asList(new ArrayList<>(), new ArrayList<>());
        }
    }

    // ========== identity_mappings.json 操作 ==========

    /**
     * 读取身份映射文件。
     * Phase 9 #2 — 统一身份映射。
     *
     * @return unifiedId -> UserIdentity 映射
     */
    public Map<String, com.memsys.identity.model.UserIdentity> readIdentityMappings() {
        try {
            Path filePath = resolvePath("identity_mappings.json");
            if (!Files.exists(filePath)) {
                return new LinkedHashMap<>();
            }
            String content = Files.readString(filePath);
            if (content.isBlank() || content.trim().equals("{}")) {
                return new LinkedHashMap<>();
            }
            return objectMapper.readValue(content,
                    objectMapper.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class,
                            com.memsys.identity.model.UserIdentity.class));
        } catch (IOException e) {
            log.error("Failed to read identity mappings", e);
            return new LinkedHashMap<>();
        }
    }

    public Map<String, com.memsys.identity.model.UserIdentity> readIdentityMappingsOrThrow() throws IOException {
        Path filePath = resolvePath("identity_mappings.json");
        if (!Files.exists(filePath)) {
            return new LinkedHashMap<>();
        }
        String content = Files.readString(filePath);
        if (content.isBlank() || content.trim().equals("{}")) {
            return new LinkedHashMap<>();
        }
        return objectMapper.readValue(content,
                objectMapper.getTypeFactory().constructMapType(
                        LinkedHashMap.class, String.class,
                        com.memsys.identity.model.UserIdentity.class));
    }

    /**
     * 写入身份映射文件。
     * Phase 9 #2 — 统一身份映射。
     */
    public void writeIdentityMappings(Map<String, com.memsys.identity.model.UserIdentity> mappings) {
        writeJsonFile("identity_mappings.json", mappings);
    }

    public List<String> listKnownScopes() {
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        scopes.add(MemoryScopeContext.DEFAULT_SCOPE);
        Path scopesRoot = basePath.resolve("scopes");
        if (!Files.exists(scopesRoot)) {
            return new ArrayList<>(scopes);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(scopesRoot)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry)) {
                    continue;
                }
                String raw = entry.getFileName().toString().replace("__", ":");
                scopes.add(MemoryScopeContext.normalize(raw));
            }
        } catch (IOException e) {
            log.warn("Failed to list scope directories", e);
        }
        return new ArrayList<>(scopes);
    }

    // ========== 通用操作 ==========

    private Map<String, Memory> readMemoryFile(String filename) {
        try {
            String content = Files.readString(resolvePath(filename));
            return objectMapper.readValue(content,
                objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Memory.class));
        } catch (IOException e) {
            log.error("Failed to read memory file: {}", filename, e);
            return new HashMap<>();
        }
    }

    private void writeJsonFile(String filename, Object data) {
        try {
            Path filePath = resolvePath(filename);
            ensureParentDirectory(filePath);
            Path tempFile = newTempFile(filename);
            ensureParentDirectory(tempFile);

            // 原子写入
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), data);
            moveWithAtomicFallback(tempFile, filePath);
        } catch (IOException e) {
            log.error("Failed to write file: {}", filename, e);
        }
    }

    private Path newTempFile(String filename) {
        return resolvePath(filename + "." + UUID.randomUUID() + ".tmp");
    }

    private Path resolvePath(String filename) {
        Path path;
        if (filename == null || filename.isBlank()) {
            path = basePath.resolve("unknown");
        } else if (GLOBAL_FILES.contains(filename)) {
            path = basePath.resolve(filename);
        } else {
            String scope = MemoryScopeContext.currentScope();
            if (MemoryScopeContext.DEFAULT_SCOPE.equals(scope)) {
                path = basePath.resolve(filename);
            } else {
                String scopeDirName = scope.replace(":", "__");
                path = basePath.resolve("scopes").resolve(scopeDirName).resolve(filename);
            }
        }
        try {
            ensureParentDirectory(path);
        } catch (IOException e) {
            log.warn("Failed to ensure parent directory for {}", path, e);
        }
        return path;
    }

    private void ensureParentDirectory(Path path) throws IOException {
        if (path == null) {
            return;
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private void moveWithAtomicFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String encodeText(String text) {
        String raw = text == null ? "" : text;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return ENCODED_TEXT_PREFIX + encoded;
    }

    private String decodeText(String value) {
        if (value == null) {
            return "";
        }
        if (!value.startsWith(ENCODED_TEXT_PREFIX)) {
            return value;
        }
        String payload = value.substring(ENCODED_TEXT_PREFIX.length());
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    private void initializeUserInsightsDocument() throws IOException {
        Path markdownPath = userInsightsMarkdownPath();
        if (Files.exists(markdownPath)) {
            return;
        }

        Path legacyJsonPath = resolvePath(LEGACY_USER_INSIGHTS_FILENAME);
        if (Files.exists(legacyJsonPath)) {
            Map<String, Memory> legacyMemories = readMemoryFile(LEGACY_USER_INSIGHTS_FILENAME);
            Path tempFile = newTempFile(USER_INSIGHTS_MARKDOWN_FILENAME);
            Files.writeString(tempFile, renderUserInsightsMarkdown(legacyMemories));
            Files.move(tempFile, markdownPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            Path backupPath = resolvePath(USER_INSIGHTS_BACKUP_FILENAME);
            Files.move(legacyJsonPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Migrated user_insights.json → user-insights.md (backup: {})", backupPath.getFileName());
            return;
        }

        Files.writeString(markdownPath, renderUserInsightsMarkdown(new LinkedHashMap<>()));
    }

    private String readUserInsightsDocument() {
        Path path = userInsightsMarkdownPath();
        try {
            if (!Files.exists(path)) {
                initializeUserInsightsDocument();
            }
            return Files.readString(path);
        } catch (IOException e) {
            log.error("Failed to read user insights markdown", e);
            try {
                return renderUserInsightsMarkdown(new LinkedHashMap<>());
            } catch (IOException fallbackException) {
                log.error("Failed to render fallback user insights markdown", fallbackException);
                return """
                    ---
                    updated_at: unknown
                    style: narrative
                    ---

                    当前还没有形成稳定的长期用户画像。
                    """;
            }
        }
    }

    private void writeUserInsights(Map<String, Memory> memories) {
        try {
            Path filePath = userInsightsMarkdownPath();
            Path tempFile = newTempFile(USER_INSIGHTS_MARKDOWN_FILENAME);
            Files.writeString(tempFile, renderUserInsightsMarkdown(memories));
            Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to write user insights markdown", e);
        }
    }

    private String renderUserInsightsMarkdown(Map<String, Memory> memories) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("version", 1);
        state.put("memories", memories);

        StringBuilder markdown = new StringBuilder();
        markdown.append("---\n");
        markdown.append("updated_at: ").append(OffsetDateTime.now()).append("\n");
        markdown.append("style: narrative\n");
        markdown.append("---\n\n");
        markdown.append(buildNarrative(memories)).append("\n\n");
        markdown.append(USER_INSIGHTS_STATE_START).append("\n");
        markdown.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state)).append("\n");
        markdown.append(USER_INSIGHTS_STATE_END).append("\n");
        return markdown.toString();
    }

    private String buildNarrative(Map<String, Memory> memories) {
        if (memories == null || memories.isEmpty()) {
            return DEFAULT_USER_INSIGHTS_NARRATIVE;
        }

        List<String> sentences = memories.entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<String, Memory> entry) ->
                                Optional.ofNullable(entry.getValue())
                                        .map(Memory::getLastAccessed)
                                        .orElse(LocalDateTime.MIN))
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .filter(Objects::nonNull)
                .map(Memory::getContent)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(content -> !content.isEmpty())
                .map(this::ensureSentenceEnding)
                .distinct()
                .toList();

        if (sentences.isEmpty()) {
            return DEFAULT_USER_INSIGHTS_NARRATIVE;
        }

        StringBuilder narrative = new StringBuilder("用户当前的长期画像如下。\n\n");
        for (int i = 0; i < sentences.size(); i++) {
            if (i > 0) {
                if (i % 4 == 0) {
                    narrative.append("\n\n");
                } else {
                    narrative.append(" ");
                }
            }
            narrative.append(sentences.get(i));
        }
        return narrative.toString();
    }

    private String ensureSentenceEnding(String content) {
        if (content.endsWith("。") || content.endsWith("！") || content.endsWith("？")
                || content.endsWith(".") || content.endsWith("!") || content.endsWith("?")) {
            return content;
        }
        return content + "。";
    }

    private UserInsightsDocument parseUserInsightsDocument(String rawContent) {
        String normalized = rawContent == null ? "" : rawContent.replace("\r\n", "\n");
        String body = stripFrontMatter(normalized);
        int stateStart = body.indexOf(USER_INSIGHTS_STATE_START);

        String narrative = stateStart >= 0 ? body.substring(0, stateStart).trim() : body.trim();
        Map<String, Memory> memories = extractStateMemories(body, stateStart);

        if (narrative.isBlank()) {
            narrative = DEFAULT_USER_INSIGHTS_NARRATIVE;
        }

        return new UserInsightsDocument(narrative, memories);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Memory> extractStateMemories(String body, int stateStart) {
        if (stateStart < 0) {
            return new LinkedHashMap<>();
        }

        int jsonStart = stateStart + USER_INSIGHTS_STATE_START.length();
        int stateEnd = body.indexOf(USER_INSIGHTS_STATE_END, jsonStart);
        if (stateEnd < 0) {
            return new LinkedHashMap<>();
        }

        String json = body.substring(jsonStart, stateEnd).trim();
        if (json.isEmpty()) {
            return new LinkedHashMap<>();
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            Object memoriesNode = parsed.get("memories");
            if (memoriesNode == null) {
                return new LinkedHashMap<>();
            }
            String memoriesJson = objectMapper.writeValueAsString(memoriesNode);
            return objectMapper.readValue(
                    memoriesJson,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Memory.class)
            );
        } catch (IOException e) {
            log.error("Failed to parse user insights markdown state", e);
            return new LinkedHashMap<>();
        }
    }

    private String stripFrontMatter(String content) {
        if (!content.startsWith("---\n")) {
            return content;
        }

        int closingMarker = content.indexOf("\n---\n", 4);
        if (closingMarker < 0) {
            return content;
        }

        return content.substring(closingMarker + 5);
    }

    private Path userInsightsMarkdownPath() {
        return resolvePath(USER_INSIGHTS_MARKDOWN_FILENAME);
    }

    private record UserInsightsDocument(String narrative, Map<String, Memory> memories) {
    }
}
