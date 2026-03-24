package com.memsys.cli;

import com.memsys.llm.LlmClient;
import com.memsys.memory.ConversationSummaryService;
import com.memsys.memory.model.Memory;
import com.memsys.memory.MemoryExtractor;
import com.memsys.memory.MemoryManager;
import com.memsys.memory.MemoryReflectionService;
import com.memsys.memory.MemoryWriteService;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.memory.model.MemoryEvidenceTrace;
import com.memsys.memory.model.ReflectionResult;
import com.memsys.prompt.AgentGuideService;
import com.memsys.prompt.SystemPromptBuilder;
import com.memsys.rag.RagService;
import com.memsys.skill.SkillService;
import com.memsys.task.ScheduledTaskService;
import com.memsys.tool.BaseTool;
import com.memsys.tool.ToolRuntimeContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 对话编排核心：只负责 processUserMessage 主流程。
 * CLI 展示逻辑和命令路由由 CliRunner 承担。
 */
@Slf4j
@Component
public class ConversationCli {

    private static final int RECENT_TURNS_FOR_MESSAGES = 10;
    private static final DateTimeFormatter TASK_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LlmClient llmClient;
    private final MemoryStorage storage;
    private final MemoryManager memoryManager;
    private final MemoryExtractor memoryExtractor;
    private final MemoryReflectionService memoryReflectionService;
    private final ConversationSummaryService conversationSummaryService;
    private final MemoryWriteService memoryWriteService;
    private final AgentGuideService agentGuideService;
    private final SystemPromptBuilder promptBuilder;
    private final com.memsys.memory.MemoryAsyncService memoryAsync;
    private final RagService ragService;
    private final SkillService skillService;
    private final ScheduledTaskService scheduledTaskService;
    private final List<BaseTool> conversationTools;
    private final int recentMessagesLimit;
    private final int topOfMindLimit;
    private final boolean ragEnabled;
    private final double ragMinScore;
    private final int ragMaxResults;

    private boolean temporaryMode = false;
    private boolean appendedStartupMapForSession = false;
    private volatile ReflectionResult lastReflectionResult = null;
    private volatile MemoryEvidenceTrace lastEvidenceTrace = null;

    public ConversationCli(
            LlmClient llmClient,
            MemoryStorage storage,
            MemoryManager memoryManager,
            MemoryExtractor memoryExtractor,
            MemoryReflectionService memoryReflectionService,
            ConversationSummaryService conversationSummaryService,
            MemoryWriteService memoryWriteService,
            AgentGuideService agentGuideService,
            SystemPromptBuilder promptBuilder,
            com.memsys.memory.MemoryAsyncService memoryAsync,
            RagService ragService,
            SkillService skillService,
            ScheduledTaskService scheduledTaskService,
            List<BaseTool> conversationTools,
            @Value("${memory.recent-messages-limit:40}") int recentMessagesLimit,
            @Value("${memory.top-of-mind-limit:15}") int topOfMindLimit,
            @Value("${rag.enabled:true}") boolean ragEnabled,
            @Value("${rag.min-similarity-score:0.35}") double ragMinScore,
            @Value("${rag.max-search-results:5}") int ragMaxResults
    ) {
        this.llmClient = llmClient;
        this.storage = storage;
        this.memoryManager = memoryManager;
        this.memoryExtractor = memoryExtractor;
        this.memoryReflectionService = memoryReflectionService;
        this.conversationSummaryService = conversationSummaryService;
        this.memoryWriteService = memoryWriteService;
        this.agentGuideService = agentGuideService;
        this.promptBuilder = promptBuilder;
        this.memoryAsync = memoryAsync;
        this.ragService = ragService;
        this.skillService = skillService;
        this.scheduledTaskService = scheduledTaskService;
        this.conversationTools = conversationTools == null ? List.of() : List.copyOf(conversationTools);
        this.recentMessagesLimit = recentMessagesLimit;
        this.topOfMindLimit = topOfMindLimit;
        this.ragEnabled = ragEnabled;
        this.ragMinScore = ragMinScore;
        this.ragMaxResults = ragMaxResults;
    }

    public void setTemporaryMode(boolean temporaryMode) {
        this.temporaryMode = temporaryMode;
    }

    public String processUserMessage(String userMessage) {
        return processUserMessage(userMessage, "", "", "");
    }

    public String processUserMessage(
            String userMessage,
            String sourcePlatform,
            String sourceConversationId,
            String sourceSenderId
    ) {
        LocalDateTime timestamp = LocalDateTime.now();
        List<Map<String, Object>> dueTaskNotifications = scheduledTaskService == null
                ? List.of()
                : scheduledTaskService.drainPendingNotificationsForConversation(sourcePlatform, sourceConversationId);

        if (temporaryMode) {
            String temporaryResponse = handleTemporaryConversation(userMessage);
            return decorateResponseWithTaskSignals(temporaryResponse, dueTaskNotifications);
        }

        // 检查全局控制
        Map<String, Object> metadata = storage.readMetadata();
        @SuppressWarnings("unchecked")
        Map<String, Object> globalControls = (Map<String, Object>) metadata.get("global_controls");
        boolean useSavedMemories = globalControls == null || (boolean) globalControls.getOrDefault("use_saved_memories", true);
        boolean useChatHistory = globalControls == null || (boolean) globalControls.getOrDefault("use_chat_history", true);

        // 1) 获取最近 10 轮完整对话作为 messages 上下文
        List<Map<String, Object>> recentTurns = useChatHistory ?
            storage.getRecentConversationTurns(RECENT_TURNS_FOR_MESSAGES) : new ArrayList<>();

        List<ChatMessage> messages = recentTurns.stream()
            .map(turn -> {
                String role = (String) turn.get("role");
                String content = (String) turn.get("message");
                if ("assistant".equals(role)) {
                    return (ChatMessage) new AiMessage(content);
                }
                return (ChatMessage) new UserMessage(content);
            })
            .collect(Collectors.toCollection(ArrayList::new));

        messages.add(new UserMessage(userMessage));

        // 2) 调用 LLM（支持按需工具调用）
        String startupMap = appendedStartupMapForSession ? "" : agentGuideService.getCachedGuide();
        String response;
        try (ToolRuntimeContext.Scope ignored = ToolRuntimeContext.bindTaskSourceContext(
                sourcePlatform,
                sourceConversationId,
                sourceSenderId
        )) {
            response = generateResponse(userMessage, useSavedMemories, useChatHistory, messages, startupMap);
        }
        appendedStartupMapForSession = true;

        // 3) 异步记忆操作
        if (useChatHistory) {
            memoryAsync.submit("persist_recent_user_message", () ->
                    storage.updateRecentMessages(userMessage, timestamp, recentMessagesLimit));
            memoryAsync.submit("append_history_user", () ->
                    storage.appendToHistory("user", userMessage, timestamp));
            memoryAsync.submit("append_history_assistant", () ->
                    storage.appendToHistory("assistant", response, LocalDateTime.now()));
        }

        if (useSavedMemories) {
            memoryAsync.submit("extract_explicit_memory", () -> {
                Map<String, Object> explicitMemory = memoryExtractor.extractExplicitMemory(userMessage);
                if ((boolean) explicitMemory.getOrDefault("has_memory", false)) {
                    handleExplicitMemoryAsync(explicitMemory, userMessage);
                }
            });
            memoryAsync.submit("update_relevant_memory_access", () -> updateMemoryAccess(userMessage));
        }

        // Phase 8: 会话摘要 — 每轮对话完成后计数，达到阈值时异步生成摘要
        if (useChatHistory && conversationSummaryService.onTurnCompleted()) {
            memoryAsync.submit("generate_session_summary", () -> {
                String summary = conversationSummaryService.generateAndPersistSummary();
                if (summary != null) {
                    log.info("Session summary generated ({} turns)", conversationSummaryService.getCurrentTurnCount());
                }
            });
        }

        return decorateResponseWithTaskSignals(response, dueTaskNotifications);
    }

    // ========== 内部方法 ==========

    private String handleTemporaryConversation(String userMessage) {
        String systemPrompt = promptBuilder.buildTemporaryPrompt();
        List<ChatMessage> messages = List.of(new UserMessage(userMessage));
        return llmClient.chat(systemPrompt, messages, 0.7);
    }

    private void handleExplicitMemoryAsync(Map<String, Object> memoryData, String rawUserMessage) {
        String slotName = (String) memoryData.get("slot_name");
        String content = (String) memoryData.get("content");
        String memoryType = (String) memoryData.get("memory_type");

        if (slotName == null || slotName.isBlank() || content == null || content.isBlank()) {
            log.warn("Explicit memory extraction returned invalid data: {}", memoryData);
            return;
        }

        Map<String, Memory> existingMemories = memoryManager.listAllMemories();
        if (existingMemories.containsKey(slotName)) {
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

        memoryWriteService.saveMemory(slotName, content, parseMemoryType(memoryType), Memory.SourceType.EXPLICIT);
        log.info("Saved explicit memory async: {} -> {}", slotName, content);
    }

    private Memory.MemoryType parseMemoryType(String memoryTypeRaw) {
        // Only USER_INSIGHT exists now
        return Memory.MemoryType.USER_INSIGHT;
    }

    private String generateResponse(String currentMessage,
                                    boolean useSavedMemories,
                                    boolean useChatHistory,
                                    List<ChatMessage> messages,
                                    String startupMap) {
        // Memory Reflection：在加载记忆前先判断是否需要
        ReflectionResult reflection = ReflectionResult.fallback();
        if (useSavedMemories) {
            try {
                String recentContext = buildRecentContextSummary(messages);
                reflection = memoryReflectionService.reflect(currentMessage, recentContext);
            } catch (Exception e) {
                log.warn("Memory reflection invocation failed, using fallback", e);
            }
        }
        this.lastReflectionResult = reflection;

        // 根据反思结果决定是否加载记忆
        boolean shouldLoadMemory = useSavedMemories && reflection.needs_memory();

        List<String> availableSkillNames = shouldLoadMemory ? skillService.listSkillNames() : List.of();
        List<LlmClient.ToolDefinition> tools = shouldLoadMemory
                ? buildConversationTools(availableSkillNames)
                : List.of();
        boolean skillToolAvailable = hasTool(tools, "load_skill");
        boolean ragToolAvailable = hasTool(tools, "search_rag");
        boolean shellToolAvailable = hasTool(tools, "run_shell");
        boolean shellCommandToolAvailable = hasTool(tools, "run_shell_command");
        boolean pythonToolAvailable = hasTool(tools, "run_python_script");
        boolean taskToolAvailable = hasTool(tools, "create_task");

        // 构建系统提示词并记录证据使用情况
        EvidenceCollector evidence = new EvidenceCollector();
        String systemPrompt = buildSystemPromptWithEvidence(
                shouldLoadMemory,
                useChatHistory,
                currentMessage,
                startupMap,
                availableSkillNames,
                skillToolAvailable,
                ragToolAvailable,
                shellToolAvailable,
                shellCommandToolAvailable,
                pythonToolAvailable,
                taskToolAvailable,
                evidence
        );

        // 记录 Memory Evidence Trace
        String truncatedMessage = currentMessage.length() > 200
                ? currentMessage.substring(0, 200) + "..."
                : currentMessage;
        this.lastEvidenceTrace = new MemoryEvidenceTrace(
                LocalDateTime.now(),
                truncatedMessage,
                reflection,
                shouldLoadMemory,
                evidence.topOfMindCount,
                evidence.ragResultCount,
                evidence.examplesUsed,
                evidence.userInsightUsed,
                availableSkillNames.size(),
                shouldLoadMemory ? "记忆已加载" : "反思判断不需要记忆，已跳过"
        );

        log.info("Memory Evidence Trace: loaded={}, topOfMind={}, rag={}, insight={}, examples={}, skills={}",
                shouldLoadMemory, evidence.topOfMindCount, evidence.ragResultCount,
                evidence.userInsightUsed, evidence.examplesUsed, availableSkillNames.size());

        return llmClient.chatWithTools(systemPrompt, messages, tools, 0.7);
    }

    /** 内部辅助类，用于在构建 system prompt 时收集证据使用数据 */
    private static class EvidenceCollector {
        int topOfMindCount = 0;
        int ragResultCount = 0;
        boolean examplesUsed = false;
        boolean userInsightUsed = false;
    }

    @SuppressWarnings("unchecked")
    private String buildSystemPromptWithEvidence(boolean useSavedMemories,
                                     boolean useChatHistory,
                                     String currentMessage,
                                     String startupMap,
                                     List<String> availableSkillNames,
                                     boolean skillToolAvailable,
                                     boolean ragToolAvailable,
                                     boolean shellToolAvailable,
                                     boolean shellCommandToolAvailable,
                                     boolean pythonToolAvailable,
                                     boolean taskToolAvailable,
                                     EvidenceCollector evidence) {
        if (!useSavedMemories && !useChatHistory) {
            return promptBuilder.buildTemporaryPrompt();
        }

        Map<String, Object> metadata = storage.readMetadata();
        Map<String, Object> userMetadata = (Map<String, Object>) metadata.get("user_interaction_metadata");
        Map<String, Object> assistantPreferences = (Map<String, Object>) metadata.get("assistant_preferences");

        List<Map.Entry<String, Memory>> topMemories = useSavedMemories ?
            memoryManager.getTopOfMindMemories(topOfMindLimit) : new ArrayList<>();

        List<Map<String, Object>> olderUserMessages = useChatHistory ?
            storage.getOlderUserMessages(RECENT_TURNS_FOR_MESSAGES, recentMessagesLimit) : new ArrayList<>();

        String userInsightsNarrative = useSavedMemories ? storage.readUserInsightsNarrative() : null;

        // RAG 语义检索
        List<RagService.RelevantMemory> ragContext = null;
        if (ragEnabled && useSavedMemories && currentMessage != null && !currentMessage.isEmpty()) {
            try {
                ragContext = ragService.buildSmartContext(currentMessage, ragMaxResults);
                Set<String> topOfMindSlots = topMemories.stream()
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
                ragContext = ragContext.stream()
                    .filter(mem -> !topOfMindSlots.contains(mem.getSlotName()))
                    .toList();
            } catch (Exception e) {
                log.warn("RAG context retrieval failed, continuing without it", e);
            }
        }

        // Examples
        String examplesContent = null;
        if (ragEnabled && useSavedMemories && currentMessage != null && !currentMessage.isEmpty()) {
            try {
                List<RagService.RelevantMemory> examples = ragService.searchExamples(currentMessage, 3, 0.3);
                if (!examples.isEmpty()) {
                    StringBuilder eb = new StringBuilder();
                    for (RagService.RelevantMemory ex : examples) {
                        String problem = (String) ex.getMetadata().getOrDefault("problem", "");
                        String solution = (String) ex.getMetadata().getOrDefault("solution", "");
                        eb.append("**Problem**: ").append(problem).append("\n");
                        eb.append("**Solution**: ").append(solution).append("\n\n");
                    }
                    examplesContent = eb.toString();
                }
            } catch (Exception e) {
                log.warn("Example search failed", e);
            }
        }

        // 收集证据使用数据
        if (evidence != null) {
            evidence.topOfMindCount = topMemories.size();
            evidence.ragResultCount = (ragContext != null) ? ragContext.size() : 0;
            evidence.examplesUsed = (examplesContent != null && !examplesContent.isBlank());
            evidence.userInsightUsed = (userInsightsNarrative != null && !userInsightsNarrative.isBlank());
        }

        return promptBuilder.buildSystemPrompt(
            startupMap,
            userMetadata,
            assistantPreferences,
            olderUserMessages,
            userInsightsNarrative,
            availableSkillNames,
            skillToolAvailable,
            ragToolAvailable,
            shellToolAvailable,
            shellCommandToolAvailable,
            pythonToolAvailable,
            taskToolAvailable,
            examplesContent,
            ragContext
        );
    }

    private boolean hasTool(List<LlmClient.ToolDefinition> tools, String name) {
        return tools.stream().anyMatch(tool -> name.equals(tool.specification().name()));
    }

    private List<LlmClient.ToolDefinition> buildConversationTools(List<String> availableSkillNames) {
        List<String> names = availableSkillNames == null ? List.of() : List.copyOf(availableSkillNames);
        return conversationTools.stream()
                .map(tool -> tool.build(names))
                .flatMap(Optional::stream)
                .toList();
    }

    private void updateMemoryAccess(String userMessage) {
        if (ragEnabled) {
            try {
                List<RagService.RelevantMemory> relevant = ragService.searchMemories(
                    userMessage, ragMaxResults, ragMinScore);
                for (RagService.RelevantMemory mem : relevant) {
                    memoryManager.updateAccessTime(mem.getSlotName());
                }
                return;
            } catch (Exception e) {
                log.warn("RAG-based memory access update failed, falling back to keyword matching", e);
            }
        }

        // 回退：关键词匹配
        Map<String, Memory> allMemories = memoryManager.listAllMemories();
        String lowerMessage = userMessage.toLowerCase();

        for (Map.Entry<String, Memory> entry : allMemories.entrySet()) {
            if (isRelevant(lowerMessage, entry.getValue())) {
                memoryManager.updateAccessTime(entry.getKey());
            }
        }
    }

    private boolean isRelevant(String userMessage, Memory memory) {
        String content = memory.getContent().toLowerCase();
        String[] contentWords = content.split("[\\s,，。.!！?？;；:：]+");
        for (String word : contentWords) {
            if (word.length() > 2 && userMessage.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取最近一轮的 Memory Reflection 结果，供 /memory-debug 等命令使用。
     */
    public ReflectionResult getLastReflectionResult() {
        return lastReflectionResult;
    }

    /**
     * 获取最近一轮的 Memory Evidence Trace，供 /memory-debug 命令展示完整证据审计。
     */
    public MemoryEvidenceTrace getLastEvidenceTrace() {
        return lastEvidenceTrace;
    }

    /**
     * 从最近的对话消息中构建简短的上下文摘要，供 Memory Reflection 参考。
     */
    private String buildRecentContextSummary(List<ChatMessage> messages) {
        if (messages == null || messages.size() <= 1) {
            return "";
        }
        // 取最近 4 条消息作为上下文（不含当前输入）
        int start = Math.max(0, messages.size() - 5);
        int end = messages.size() - 1;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            ChatMessage msg = messages.get(i);
            String role = (msg instanceof AiMessage) ? "assistant" : "user";
            String text = (msg instanceof AiMessage ai) ? ai.text() : ((UserMessage) msg).singleText();
            // 截断过长的消息
            if (text.length() > 200) {
                text = text.substring(0, 200) + "...";
            }
            sb.append(role).append(": ").append(text).append("\n");
        }
        return sb.toString();
    }

    private String decorateResponseWithTaskSignals(String response,
                                                   List<Map<String, Object>> dueTaskNotifications) {
        List<String> additions = new ArrayList<>();

        if (dueTaskNotifications != null && !dueTaskNotifications.isEmpty()) {
            additions.add("[提醒] 以下定时任务已到时间：");
            for (Map<String, Object> notification : dueTaskNotifications) {
                String title = String.valueOf(notification.getOrDefault("task_title", "未命名任务")).trim();
                String dueRaw = String.valueOf(notification.getOrDefault("due_at", "")).trim();
                String dueAt = dueRaw.isEmpty() ? "未知时间" : formatTaskTime(dueRaw);
                additions.add("- " + title + "（计划时间 " + dueAt + "）");
            }
        }

        if (additions.isEmpty()) {
            return response;
        }
        return String.join("\n", additions) + "\n\n" + response;
    }

    private String formatTaskTime(String dueRaw) {
        try {
            return LocalDateTime.parse(dueRaw).format(TASK_TIME_FORMATTER);
        } catch (Exception ignored) {
            return dueRaw;
        }
    }
}
