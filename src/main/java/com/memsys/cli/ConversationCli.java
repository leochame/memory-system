package com.memsys.cli;

import com.memsys.llm.LlmClient;
import com.memsys.memory.Memory;
import com.memsys.memory.MemoryExtractor;
import com.memsys.memory.MemoryManager;
import com.memsys.memory.MemoryStorage;
import com.memsys.prompt.SystemPromptBuilder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ConversationCli {

    private final LlmClient llmClient;
    private final MemoryStorage storage;
    private final MemoryManager memoryManager;
    private final MemoryExtractor memoryExtractor;
    private final SystemPromptBuilder promptBuilder;
    private final com.memsys.memory.MemoryAsyncService memoryAsync;
    private final int recentMessagesLimit;
    private final int topOfMindLimit;
    private final int maxSlots;
    private final int daysUnaccessed;

    private boolean temporaryMode = false;

    public ConversationCli(
            LlmClient llmClient,
            MemoryStorage storage,
            MemoryManager memoryManager,
            MemoryExtractor memoryExtractor,
            SystemPromptBuilder promptBuilder,
            com.memsys.memory.MemoryAsyncService memoryAsync,
            @Value("${memory.recent-messages-limit:40}") int recentMessagesLimit,
            @Value("${memory.top-of-mind-limit:15}") int topOfMindLimit,
            @Value("${memory.max-slots:100}") int maxSlots,
            @Value("${memory.days-unaccessed:30}") int daysUnaccessed
    ) {
        this.llmClient = llmClient;
        this.storage = storage;
        this.memoryManager = memoryManager;
        this.memoryExtractor = memoryExtractor;
        this.promptBuilder = promptBuilder;
        this.memoryAsync = memoryAsync;
        this.recentMessagesLimit = recentMessagesLimit;
        this.topOfMindLimit = topOfMindLimit;
        this.maxSlots = maxSlots;
        this.daysUnaccessed = daysUnaccessed;
    }

    public void setTemporaryMode(boolean temporaryMode) {
        this.temporaryMode = temporaryMode;
    }

    public String processUserMessage(String userMessage) {
        LocalDateTime timestamp = LocalDateTime.now();

        // 临时模式：不读取记忆，不保存历史
        if (temporaryMode) {
            return handleTemporaryConversation(userMessage);
        }

        // 检查全局控制
        Map<String, Object> metadata = storage.readMetadata();
        Map<String, Object> globalControls = (Map<String, Object>) metadata.get("global_controls");
        boolean useSavedMemories = globalControls == null || (boolean) globalControls.getOrDefault("use_saved_memories", true);
        boolean useChatHistory = globalControls == null || (boolean) globalControls.getOrDefault("use_chat_history", true);

        // 1) 构建系统提示词（主线程保留：用于本次对话回复）
        String systemPrompt = buildSystemPrompt(useSavedMemories, useChatHistory);

        // 2) 获取最近 10 轮完整对话作为 messages 列表上下文
        // 一轮对话 = 一条用户消息 + 一条助手回复
        List<Map<String, Object>> recentTurns = useChatHistory ?
            storage.getRecentConversationTurns(10) : new ArrayList<>();

        // 使用 LangChain4j 的 ChatMessage 结构，而不是自定义 Map
        List<ChatMessage> messages = recentTurns.stream()
            .map(turn -> {
                String role = (String) turn.get("role");
                String content = (String) turn.get("message");
                if ("assistant".equals(role)) {
                    return (ChatMessage) new AiMessage(content);
                }
                // 其它情况（包括 "user"）都按用户消息处理
                return (ChatMessage) new UserMessage(content);
            })
            .collect(Collectors.toCollection(ArrayList::new));

        // 追加当前这一轮用户输入
        messages.add(new UserMessage(userMessage));

        // 3) 调用 LLM（主线程关键路径）
        String response = llmClient.chat(systemPrompt, messages, 0.7);

        // 4) 记忆相关操作：全部异步（单核心线程池），不阻塞主任务
        if (useChatHistory) {
            memoryAsync.submit("persist_recent_user_message", () ->
                    storage.updateRecentMessages(userMessage, timestamp, recentMessagesLimit));
            memoryAsync.submit("append_history_user", () ->
                    storage.appendToHistory("user", userMessage, timestamp));
            memoryAsync.submit("append_history_assistant", () ->
                    storage.appendToHistory("assistant", response, LocalDateTime.now()));
        }

        if (useSavedMemories) {
            // 异步显式记忆提取（可能会调用 LLM，耗时较长）
            memoryAsync.submit("extract_explicit_memory", () -> {
                Map<String, Object> explicitMemory = memoryExtractor.extractExplicitMemory(userMessage);
                if ((boolean) explicitMemory.getOrDefault("has_memory", false)) {
                    handleExplicitMemoryAsync(explicitMemory, userMessage);
                }
            });

            // 基于关键词匹配更新相关记忆的访问时间（会触发文件写入，异步化）
            memoryAsync.submit("update_relevant_memory_access", () -> updateMemoryAccess(userMessage));
        }

        return response;
    }

    private String handleTemporaryConversation(String userMessage) {
        String systemPrompt = promptBuilder.buildTemporaryPrompt();
        List<ChatMessage> messages = List.of(new UserMessage(userMessage));
        return llmClient.chat(systemPrompt, messages, 0.7);
    }

    private void handleExplicitMemory(Map<String, Object> memoryData) {
        String slotName = (String) memoryData.get("slot_name");
        String content = (String) memoryData.get("content");
        String memoryType = (String) memoryData.get("memory_type");

        // 检查冲突
        Map<String, Memory> existingMemories = memoryManager.listAllMemories();
        String conflict = memoryManager.detectConflict(slotName, existingMemories);

        if (conflict != null) {
            Memory existingMemory = existingMemories.get(slotName);
            System.out.println("\n⚠️  检测到记忆冲突！");
            System.out.println("槽位名: " + slotName);
            System.out.println("原值: " + existingMemory.getContent());
            System.out.println("新值: " + content);
            System.out.print("是否覆盖？(y/n): ");

            Scanner scanner = new Scanner(System.in);
            String answer = scanner.nextLine().trim().toLowerCase();

            if (!answer.equals("y") && !answer.equals("yes")) {
                System.out.println("已取消覆盖，保留原记忆。");
                return;
            }
            System.out.println("已确认覆盖。\n");
        }

        // 创建新记忆
        Memory memory = new Memory();
        memory.setContent(content);
        memory.setMemoryType(Memory.MemoryType.valueOf(memoryType.toUpperCase()));
        memory.setSource(Memory.SourceType.EXPLICIT);
        memory.setHitCount(0);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setLastAccessed(LocalDateTime.now());

        // 保存记忆
        if (memory.getMemoryType() == Memory.MemoryType.MODEL_SET_CONTEXT) {
            storage.writeModelSetContext(slotName, memory);
        } else {
            storage.writeImplicitMemory(slotName, memory);
        }

        // 更新队列
        memoryManager.updateAccessTime(slotName);

        log.info("已保存显式记忆: {} -> {}", slotName, content);
    }

    /**
     * 异步路径下的显式记忆处理：不做任何需要同步用户输入的交互。
     * - 无冲突：直接写入
     * - 有冲突：不覆盖，写入 pending inbox（供后续人工处理）
     */
    private void handleExplicitMemoryAsync(Map<String, Object> memoryData, String rawUserMessage) {
        String slotName = (String) memoryData.get("slot_name");
        String content = (String) memoryData.get("content");
        String memoryType = (String) memoryData.get("memory_type");

        if (slotName == null || slotName.isBlank() || content == null || content.isBlank()) {
            log.warn("Explicit memory extraction returned invalid data: {}", memoryData);
            return;
        }

        Map<String, Memory> existingMemories = memoryManager.listAllMemories();
        boolean conflict = existingMemories.containsKey(slotName);

        if (conflict) {
            Memory existing = existingMemories.get(slotName);
            Map<String, Object> record = new HashMap<>();
            record.put("timestamp", LocalDateTime.now().toString());
            record.put("reason", "conflict");
            record.put("slot_name", slotName);
            record.put("new_content", content);
            record.put("existing_content", existing == null ? null : existing.getContent());
            record.put("memory_type", memoryType);
            record.put("raw_user_message", rawUserMessage);
            storage.appendPendingExplicitMemory(record);
            log.info("Explicit memory conflict; wrote to pending inbox: {}", slotName);
            return;
        }

        Memory memory = new Memory();
        memory.setContent(content);
        memory.setMemoryType(parseMemoryType(memoryType));
        memory.setSource(Memory.SourceType.EXPLICIT);
        memory.setHitCount(0);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setLastAccessed(LocalDateTime.now());

        if (memory.getMemoryType() == Memory.MemoryType.MODEL_SET_CONTEXT) {
            storage.writeModelSetContext(slotName, memory);
        } else {
            storage.writeImplicitMemory(slotName, memory);
        }

        memoryManager.updateAccessTime(slotName);
        log.info("Saved explicit memory async: {} -> {}", slotName, content);
    }

    private Memory.MemoryType parseMemoryType(String memoryTypeRaw) {
        if (memoryTypeRaw == null) {
            return Memory.MemoryType.USER_INSIGHT;
        }
        String norm = memoryTypeRaw.trim().toUpperCase(Locale.ROOT);
        // 常见 LLM 输出：user_insight / notable_highlights / model_set_context
        try {
            return Memory.MemoryType.valueOf(norm);
        } catch (Exception ignored) {
            // 尝试把非字母数字替换为下划线再解析
            String normalized = norm.replaceAll("[^A-Z0-9]+", "_");
            try {
                return Memory.MemoryType.valueOf(normalized);
            } catch (Exception ignored2) {
                return Memory.MemoryType.USER_INSIGHT;
            }
        }
    }

    private String buildSystemPrompt(boolean useSavedMemories, boolean useChatHistory) {
        if (!useSavedMemories && !useChatHistory) {
            return promptBuilder.buildTemporaryPrompt();
        }

        Map<String, Object> metadata = storage.readMetadata();
        Map<String, Object> userMetadata = (Map<String, Object>) metadata.get("user_interaction_metadata");
        Map<String, Object> assistantPreferences = (Map<String, Object>) metadata.get("assistant_preferences");

        // 获取 Top of Mind 记忆
        List<Map.Entry<String, Memory>> topMemories = useSavedMemories ?
            memoryManager.getTopOfMindMemories(topOfMindLimit) : new ArrayList<>();

        Map<String, Memory> modelSetContext = new HashMap<>();
        Map<String, Memory> notableHighlights = new HashMap<>();
        Map<String, Memory> userInsights = new HashMap<>();

        for (Map.Entry<String, Memory> entry : topMemories) {
            Memory memory = entry.getValue();
            switch (memory.getMemoryType()) {
                case MODEL_SET_CONTEXT -> modelSetContext.put(entry.getKey(), memory);
                case NOTABLE_HIGHLIGHTS -> notableHighlights.put(entry.getKey(), memory);
                case USER_INSIGHT -> userInsights.put(entry.getKey(), memory);
            }
        }

        // 获取第 11-40 轮的用户消息，用于系统提示词中的"最近对话内容"模块
        // 这作为"连续性日志"连接过去的讨论与当前对话
        List<Map<String, Object>> olderUserMessages = useChatHistory ?
            storage.getOlderUserMessages(10, 40) : new ArrayList<>();

        return promptBuilder.buildSystemPrompt(
            userMetadata,
            assistantPreferences,
            olderUserMessages, // 第 11-40 轮的用户消息，用于最近对话内容模块
            modelSetContext,
            notableHighlights,
            userInsights
        );
    }

    private void updateMemoryAccess(String userMessage) {
        // 基于关键词匹配更新相关记忆的访问时间
        Map<String, Memory> allMemories = memoryManager.listAllMemories();
        String lowerMessage = userMessage.toLowerCase();

        for (Map.Entry<String, Memory> entry : allMemories.entrySet()) {
            String slotName = entry.getKey();
            Memory memory = entry.getValue();

            // 基础相关性判断：检查用户消息是否与记忆内容相关
            if (isRelevant(lowerMessage, memory)) {
                memoryManager.updateAccessTime(slotName);
                log.debug("Updated access time for relevant memory: {}", slotName);
            }
        }
    }

    private boolean isRelevant(String userMessage, Memory memory) {
        String content = memory.getContent().toLowerCase();

        // 提取记忆内容中的关键词（简单实现：长度>2的词）
        String[] contentWords = content.split("[\\s,，。.!！?？;；:：]+");

        // 如果用户消息包含记忆内容中的关键词，则认为相关
        for (String word : contentWords) {
            if (word.length() > 2 && userMessage.contains(word)) {
                return true;
            }
        }

        return false;
    }

    public void showMemories() {
        Map<String, Memory> memories = memoryManager.listAllMemories();
        System.out.println("\n=== 当前记忆列表 ===");
        memories.forEach((slotName, memory) -> {
            System.out.printf("[%s] %s\n", slotName, memory.getContent());
            System.out.printf("  类型: %s | 来源: %s | 访问次数: %d | 最后访问: %s\n",
                memory.getMemoryType(), memory.getSource(), memory.getHitCount(), memory.getLastAccessed());
        });
        System.out.println("===================\n");
    }

    public void deleteMemory(String slotName) {
        memoryManager.deleteMemory(slotName);
        System.out.printf("已删除记忆: %s\n", slotName);
    }

    public void cleanupOldMemories() {
        List<String> deleted = memoryManager.cleanupOldMemories(maxSlots, daysUnaccessed);
        System.out.printf("已清理 %d 条长期未访问的记忆（阈值：%d天未访问，最大槽位数：%d）\n",
            deleted.size(), daysUnaccessed, maxSlots);
    }

    public void editMemory(String slotName) {
        Memory memory = memoryManager.getMemory(slotName);
        if (memory == null) {
            System.out.printf("未找到记忆: %s\n", slotName);
            return;
        }

        System.out.println("\n当前记忆内容:");
        System.out.println(memory.getContent());
        System.out.print("\n请输入新内容（直接回车取消）: ");

        Scanner scanner = new Scanner(System.in);
        String newContent = scanner.nextLine().trim();

        if (newContent.isEmpty()) {
            System.out.println("已取消编辑。\n");
            return;
        }

        memoryManager.editMemory(slotName, newContent);
        System.out.printf("已更新记忆: %s\n\n", slotName);
    }

    public void showControls() {
        Map<String, Object> metadata = storage.readMetadata();
        Map<String, Object> globalControls = (Map<String, Object>) metadata.get("global_controls");

        boolean useSavedMemories = globalControls == null || (boolean) globalControls.getOrDefault("use_saved_memories", true);
        boolean useChatHistory = globalControls == null || (boolean) globalControls.getOrDefault("use_chat_history", true);

        System.out.println("\n=== 全局控制设置 ===");
        System.out.printf("使用保存的记忆: %s\n", useSavedMemories ? "开启" : "关闭");
        System.out.printf("使用对话历史: %s\n", useChatHistory ? "开启" : "关闭");
        System.out.println("===================\n");
    }

    public void setControl(String controlName, String value) {
        Map<String, Object> metadata = storage.readMetadata();
        Map<String, Object> globalControls = (Map<String, Object>) metadata.getOrDefault("global_controls", new HashMap<>());

        boolean boolValue = value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true");

        switch (controlName.toLowerCase()) {
            case "use_memories":
            case "memories":
                globalControls.put("use_saved_memories", boolValue);
                metadata.put("global_controls", globalControls);
                storage.writeMetadata(metadata);
                System.out.printf("已%s使用保存的记忆\n\n", boolValue ? "开启" : "关闭");
                break;

            case "use_history":
            case "history":
                globalControls.put("use_chat_history", boolValue);
                metadata.put("global_controls", globalControls);
                storage.writeMetadata(metadata);
                System.out.printf("已%s使用对话历史\n\n", boolValue ? "开启" : "关闭");
                break;

            default:
                System.out.println("未知的控制项: " + controlName);
                System.out.println("可用的控制项: memories, history\n");
        }
    }

    public void triggerMemoryUpdate() {
        System.out.println("已提交后台任务：提取隐式记忆和用户档案（不阻塞主对话）。");

        memoryAsync.submit("manual_memory_update", () -> {
            // 获取最近的对话历史
            LocalDateTime startDate = LocalDateTime.now().minusDays(7);
            List<Map<String, Object>> recentHistory = storage.getHistory(startDate, null);

            if (recentHistory.isEmpty()) {
                log.info("Manual memory update: not enough history, skip.");
                return;
            }

            // 提取用户档案
            List<Map<String, Object>> insights = memoryExtractor.extractUserInsights(recentHistory, "manual");
            for (Map<String, Object> insight : insights) {
                String slotName = (String) insight.get("slot_name");
                String content = (String) insight.get("content");

                Memory memory = new Memory();
                memory.setContent(content);
                memory.setMemoryType(Memory.MemoryType.USER_INSIGHT);
                memory.setSource(Memory.SourceType.IMPLICIT);
                memory.setHitCount(0);
                memory.setCreatedAt(LocalDateTime.now());
                memory.setLastAccessed(LocalDateTime.now());
                memory.setConfidence((String) insight.getOrDefault("confidence", "medium"));

                storage.writeImplicitMemory(slotName, memory);
                memoryManager.updateAccessTime(slotName);
            }

            // 提取显著话题
            List<Map<String, Object>> highlights = memoryExtractor.extractNotableHighlights(recentHistory);
            for (Map<String, Object> highlight : highlights) {
                String slotName = (String) highlight.get("slot_name");
                String content = (String) highlight.get("content");

                Memory memory = new Memory();
                memory.setContent(content);
                memory.setMemoryType(Memory.MemoryType.NOTABLE_HIGHLIGHTS);
                memory.setSource(Memory.SourceType.IMPLICIT);
                memory.setHitCount(0);
                memory.setCreatedAt(LocalDateTime.now());
                memory.setLastAccessed(LocalDateTime.now());

                storage.writeImplicitMemory(slotName, memory);
                memoryManager.updateAccessTime(slotName);
            }

            log.info("Manual memory update done. insights={}, highlights={}", insights.size(), highlights.size());
        });
    }

    public void showWhatYouKnow() {
        Map<String, Memory> allMemories = memoryManager.listAllMemories();

        if (allMemories.isEmpty()) {
            System.out.println("\n我目前还没有记住任何关于你的信息。\n");
            return;
        }

        System.out.println("\n=== 我记得关于你的信息 ===\n");

        // 按类型分组显示
        Map<Memory.MemoryType, List<Map.Entry<String, Memory>>> grouped = allMemories.entrySet().stream()
            .collect(Collectors.groupingBy(e -> e.getValue().getMemoryType()));

        // 用户档案
        if (grouped.containsKey(Memory.MemoryType.USER_INSIGHT)) {
            System.out.println("【用户档案】");
            grouped.get(Memory.MemoryType.USER_INSIGHT).forEach(entry -> {
                System.out.println("  • " + entry.getValue().getContent());
            });
            System.out.println();
        }

        // 对话摘要
        if (grouped.containsKey(Memory.MemoryType.MODEL_SET_CONTEXT)) {
            System.out.println("【对话摘要】");
            grouped.get(Memory.MemoryType.MODEL_SET_CONTEXT).forEach(entry -> {
                System.out.println("  • " + entry.getValue().getContent());
            });
            System.out.println();
        }

        // 显著话题
        if (grouped.containsKey(Memory.MemoryType.NOTABLE_HIGHLIGHTS)) {
            System.out.println("【讨论话题】");
            grouped.get(Memory.MemoryType.NOTABLE_HIGHLIGHTS).forEach(entry -> {
                System.out.println("  • " + entry.getValue().getContent());
            });
            System.out.println();
        }

        System.out.println("=".repeat(40) + "\n");
    }
}
