package com.memsys.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MemoryStorage {

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

            // 初始化文件
            createFileIfNotExists("metadata.json", "{}");
            createFileIfNotExists("model_set_context.json", "{}");
            createFileIfNotExists("implicit_memories.json", "{}");
            createFileIfNotExists("recent_user_messages.jsonl", "");
            createFileIfNotExists("conversation_history.jsonl", "");
            createFileIfNotExists("memory_queues.json", "{\"young_queue\":[],\"mature_queue\":[]}");
            createFileIfNotExists("pending_explicit_memories.jsonl", "");

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

    // ========== model_set_context.json 操作 ==========

    public Map<String, Memory> readModelSetContext() {
        return readMemoryFile("model_set_context.json");
    }

    public void writeModelSetContext(String slotName, Memory memory) {
        Map<String, Memory> context = readModelSetContext();
        context.put(slotName, memory);
        writeJsonFile("model_set_context.json", context);
    }

    public void deleteModelSetContext(String slotName) {
        Map<String, Memory> context = readModelSetContext();
        context.remove(slotName);
        writeJsonFile("model_set_context.json", context);
    }

    // ========== implicit_memories.json 操作 ==========

    public Map<String, Memory> readImplicitMemories() {
        return readMemoryFile("implicit_memories.json");
    }

    public void writeImplicitMemory(String slotName, Memory memory) {
        Map<String, Memory> memories = readImplicitMemories();
        memories.put(slotName, memory);
        writeJsonFile("implicit_memories.json", memories);
    }

    public void deleteImplicitMemory(String slotName) {
        Map<String, Memory> memories = readImplicitMemories();
        memories.remove(slotName);
        writeJsonFile("implicit_memories.json", memories);
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
}
