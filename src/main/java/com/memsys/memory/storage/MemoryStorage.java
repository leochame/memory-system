package com.memsys.memory.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.memsys.memory.model.Memory;
import com.memsys.task.model.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
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

            // 迁移：旧文件 implicit_memories.json → user_insights.json
            Path oldImplicit = basePath.resolve("implicit_memories.json");
            Path newInsights = basePath.resolve(LEGACY_USER_INSIGHTS_FILENAME);
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

            log.info("Memory storage initialized at: {}", basePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to initialize storage", e);
            throw new RuntimeException("Failed to initialize storage", e);
        }
    }

    private void createFileIfNotExists(String filename, String defaultContent) throws IOException {
        Path filePath = basePath.resolve(filename);
        if (!Files.exists(filePath)) {
            Files.writeString(filePath, defaultContent);
        }
    }

    // ========== metadata.json 操作 ==========

    public Map<String, Object> readMetadata() {
        try {
            String content = Files.readString(basePath.resolve("metadata.json"));
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
        try {
            Path filePath = basePath.resolve("recent_user_messages.jsonl");
            List<String> lines = Files.exists(filePath) ?
                Files.readAllLines(filePath) : new ArrayList<>();

            // 添加新消息
            String newLine = formatter.format(timestamp) + "|" + message;
            lines.add(newLine);

            // 保持滚动窗口
            if (lines.size() > limit) {
                lines = lines.subList(lines.size() - limit, lines.size());
            }

            // 原子写入，避免并发读写导致文件被截断或写入不完整
            Path tempFile = basePath.resolve("recent_user_messages.jsonl.tmp");
            Files.writeString(tempFile, String.join("\n", lines) + "\n");
            Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to update recent messages", e);
        }
    }

    public List<Map<String, Object>> getRecentMessages(int limit) {
        try {
            Path filePath = basePath.resolve("recent_user_messages.jsonl");
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
                        msg.put("message", parts[1]);
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
            Path filePath = basePath.resolve("conversation_history.jsonl");
            String line = formatter.format(timestamp) + "|" + role + "|" + message + "\n";
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
            Path filePath = basePath.resolve("pending_explicit_memories.jsonl");
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
            Path filePath = basePath.resolve("pending_explicit_memories.jsonl");
            if (!Files.exists(filePath)) {
                return results;
            }
            List<String> lines = Files.readAllLines(filePath);
            for (String line : lines) {
                if (line.isBlank()) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> record = objectMapper.readValue(line, Map.class);
                results.add(record);
            }
        } catch (IOException e) {
            log.error("Failed to read pending explicit memories", e);
        }
        return results;
    }

    // ========== scheduled_tasks.json 操作 ==========

    public List<ScheduledTask> readScheduledTasks() {
        try {
            Path filePath = basePath.resolve("scheduled_tasks.json");
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
            Path filePath = basePath.resolve("pending_task_notifications.jsonl");
            String line = objectMapper.writeValueAsString(record) + "\n";
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Failed to append pending task notification", e);
        }
    }

    public List<Map<String, Object>> drainPendingTaskNotifications() {
        try {
            Path filePath = basePath.resolve("pending_task_notifications.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }
            List<String> lines = Files.readAllLines(filePath);
            List<Map<String, Object>> notifications = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                notifications.add(objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {
                }));
            }
            Files.writeString(filePath, "");
            return notifications;
        } catch (IOException e) {
            log.error("Failed to drain pending task notifications", e);
            return new ArrayList<>();
        }
    }

    public List<Map<String, Object>> getHistory(LocalDateTime startDate, LocalDateTime endDate) {
        try {
            Path filePath = basePath.resolve("conversation_history.jsonl");
            if (!Files.exists(filePath)) {
                return new ArrayList<>();
            }

            return Files.readAllLines(filePath).stream()
                .map(line -> {
                    String[] parts = line.split("\\|", 3);
                    Map<String, Object> entry = new HashMap<>();
                    if (parts.length == 3) {
                        LocalDateTime timestamp = LocalDateTime.parse(parts[0], formatter);
                        if ((startDate == null || !timestamp.isBefore(startDate)) &&
                            (endDate == null || !timestamp.isAfter(endDate))) {
                            entry.put("timestamp", parts[0]);
                            entry.put("role", parts[1]);
                            entry.put("message", parts[2]);
                            return entry;
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
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
        try {
            Path filePath = basePath.resolve("conversation_history.jsonl");
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
                    entry.put("message", parts[2]);
                    allMessages.add(0, entry); // 插入到开头，保持时间顺序
                }
            }

            // 计算需要返回的消息数量：N 轮 = N 条用户消息 + N 条助手消息 = 2N 条消息
            int messageCount = turns * 2;
            if (allMessages.size() <= messageCount) {
                return allMessages;
            }

            // 返回最后 2N 条消息
            return allMessages.subList(allMessages.size() - messageCount, allMessages.size());
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
        try {
            Path filePath = basePath.resolve("conversation_history.jsonl");
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
                    entry.put("message", parts[2]);
                    allMessages.add(0, entry); // 插入到开头，保持时间顺序
                }
            }

            // 计算需要返回的用户消息范围
            // 第 1 轮 = 索引 0（用户）、索引 1（助手）
            // 第 10 轮 = 索引 18（用户）、索引 19（助手）
            // 第 11 轮 = 索引 20（用户）、索引 21（助手）
            // 第 40 轮 = 索引 78（用户）、索引 79（助手）
            // 所以第 11-40 轮的用户消息索引 = 20, 22, 24, ..., 78（偶数索引，从 0 开始）

            int startIndex = startTurn * 2; // 第 startTurn+1 轮的用户消息索引（偶数）
            int endIndex = endTurn * 2; // 第 endTurn 轮的用户消息索引（偶数）

            if (allMessages.size() <= startIndex) {
                return new ArrayList<>();
            }

            List<Map<String, Object>> userMessages = new ArrayList<>();
            for (int i = startIndex; i < Math.min(allMessages.size(), endIndex + 1); i += 2) {
                Map<String, Object> msg = allMessages.get(i);
                if ("user".equals(msg.get("role"))) {
                    Map<String, Object> userMsg = new HashMap<>();
                    userMsg.put("timestamp", msg.get("timestamp"));
                    userMsg.put("message", msg.get("message"));
                    userMessages.add(userMsg);
                }
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
            Path filePath = basePath.resolve("session_summaries.jsonl");
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
            Path filePath = basePath.resolve("session_summaries.jsonl");
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

    // ========== proactive_reminders.jsonl 操作 ==========

    /**
     * 追加一条主动提醒记录到 proactive_reminders.jsonl。
     * Phase 9 #5 — 基于记忆生成主动提醒。
     */
    public void appendProactiveReminder(Map<String, Object> reminderRecord) {
        try {
            Path filePath = basePath.resolve("proactive_reminders.jsonl");
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
            Path filePath = basePath.resolve("proactive_reminders.jsonl");
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

    // ========== memory_queues.json 操作 ==========

    public void saveQueues(List<String> youngQueue, List<String> matureQueue) {
        Map<String, List<String>> queues = new HashMap<>();
        queues.put("young_queue", youngQueue);
        queues.put("mature_queue", matureQueue);
        writeJsonFile("memory_queues.json", queues);
    }

    public List<List<String>> loadQueues() {
        try {
            String content = Files.readString(basePath.resolve("memory_queues.json"));
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

    // ========== 通用操作 ==========

    private Map<String, Memory> readMemoryFile(String filename) {
        try {
            String content = Files.readString(basePath.resolve(filename));
            return objectMapper.readValue(content,
                objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Memory.class));
        } catch (IOException e) {
            log.error("Failed to read memory file: {}", filename, e);
            return new HashMap<>();
        }
    }

    private void writeJsonFile(String filename, Object data) {
        try {
            Path filePath = basePath.resolve(filename);
            Path tempFile = basePath.resolve(filename + ".tmp");

            // 原子写入
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), data);
            Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to write file: {}", filename, e);
        }
    }

    private void initializeUserInsightsDocument() throws IOException {
        Path markdownPath = userInsightsMarkdownPath();
        if (Files.exists(markdownPath)) {
            return;
        }

        Path legacyJsonPath = basePath.resolve(LEGACY_USER_INSIGHTS_FILENAME);
        if (Files.exists(legacyJsonPath)) {
            Map<String, Memory> legacyMemories = readMemoryFile(LEGACY_USER_INSIGHTS_FILENAME);
            Path tempFile = basePath.resolve(USER_INSIGHTS_MARKDOWN_FILENAME + ".tmp");
            Files.writeString(tempFile, renderUserInsightsMarkdown(legacyMemories));
            Files.move(tempFile, markdownPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            Path backupPath = basePath.resolve(USER_INSIGHTS_BACKUP_FILENAME);
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
            Path tempFile = basePath.resolve(USER_INSIGHTS_MARKDOWN_FILENAME + ".tmp");
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
                                Optional.ofNullable(entry.getValue().getLastAccessed()).orElse(LocalDateTime.MIN))
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue)
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
        return basePath.resolve(USER_INSIGHTS_MARKDOWN_FILENAME);
    }

    private record UserInsightsDocument(String narrative, Map<String, Memory> memories) {
    }
}
