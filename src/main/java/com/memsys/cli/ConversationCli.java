package com.memsys.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int BASE_PROFILE_MAX_CHARS = 800;
    private static final int FAST_RAG_MAX_RESULTS = 3;
    private static final int FAST_EXAMPLE_MAX_RESULTS = 2;
    private static final ObjectMapper TRACE_PARSER = new ObjectMapper();
    private static final String DELIMITED_LIST_SPLIT_REGEX = "[,;|\\n，；]+";

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

    private volatile ReflectionResult lastReflectionResult = null;
    private volatile MemoryEvidenceTrace lastEvidenceTrace = null;
    private final AtomicBoolean startupMapInjected = new AtomicBoolean(false);

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

    public String processUserMessage(String userMessage) {
        return processUserMessage(userMessage, "", "", "");
    }

    public String processUserMessageTemporary(String userMessage) {
        return processUserMessageTemporary(userMessage, "", "", "");
    }

    /**
     * 评测专用临时模式：仅生成回答，不读取/清空任务通知，也不附加任务提示。
     */
    public String processUserMessageTemporaryForEval(String userMessage) {
        return handleTemporaryConversation(userMessage);
    }

    public String processUserMessageTemporary(
            String userMessage,
            String sourcePlatform,
            String sourceConversationId,
            String sourceSenderId
    ) {
        return processUserMessageTemporary(userMessage, sourcePlatform, sourceConversationId, sourceSenderId, null);
    }

    public String processUserMessageTemporary(
            String userMessage,
            String sourcePlatform,
            String sourceConversationId,
            String sourceSenderId,
            ConversationProgressListener progressListener
    ) {
        emitProgress(progressListener, "accepted", "已收到消息，开始处理。");
        List<Map<String, Object>> dueTaskNotifications = scheduledTaskService == null
                ? List.of()
                : scheduledTaskService.drainPendingNotificationsForConversation(sourcePlatform, sourceConversationId);
        emitProgress(progressListener, "temporary_mode", "临时会话模式：跳过长期记忆读写。");
        String temporaryResponse = handleTemporaryConversation(userMessage, progressListener);
        emitProgress(progressListener, "completed", "已完成回答生成。");
        return decorateResponseWithTaskSignals(temporaryResponse, dueTaskNotifications);
    }

    /**
     * 评测专用有记忆模式：读取上下文并生成回答，但不产生任何落盘副作用。
     */
    public String processUserMessageWithMemoryForEval(String userMessage) {
        // 检查全局控制（与正常主链路保持一致）
        Map<String, Object> metadata = storage.readMetadata();
        @SuppressWarnings("unchecked")
        Map<String, Object> globalControls = (Map<String, Object>) metadata.get("global_controls");
        boolean useSavedMemories = globalControls == null
                || parseBoolean(globalControls.get("use_saved_memories"), true);
        boolean useChatHistory = globalControls == null
                || parseBoolean(globalControls.get("use_chat_history"), true);

        List<Map<String, Object>> recentTurns = useChatHistory
                ? storage.getRecentConversationTurns(RECENT_TURNS_FOR_MESSAGES)
                : new ArrayList<>();

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

        String startupMap = startupMapForEval();
        return generateResponseForEvalWithoutTools(userMessage, useSavedMemories, useChatHistory, messages, startupMap);
    }

    public String processUserMessage(
            String userMessage,
            String sourcePlatform,
            String sourceConversationId,
            String sourceSenderId
    ) {
        return processUserMessage(userMessage, sourcePlatform, sourceConversationId, sourceSenderId, null);
    }

    public String processUserMessage(
            String userMessage,
            String sourcePlatform,
            String sourceConversationId,
            String sourceSenderId,
            ConversationProgressListener progressListener
    ) {
        long requestStartNs = System.nanoTime();
        emitProgress(progressListener, "accepted", "已收到消息，开始处理。");
        LocalDateTime timestamp = LocalDateTime.now();
        List<Map<String, Object>> dueTaskNotifications = scheduledTaskService == null
                ? List.of()
                : scheduledTaskService.drainPendingNotificationsForConversation(sourcePlatform, sourceConversationId);

        // 检查全局控制
        Map<String, Object> metadata = storage.readMetadata();
        @SuppressWarnings("unchecked")
        Map<String, Object> globalControls = (Map<String, Object>) metadata.get("global_controls");
        boolean useSavedMemories = globalControls == null
                || parseBoolean(globalControls.get("use_saved_memories"), true);
        boolean useChatHistory = globalControls == null
                || parseBoolean(globalControls.get("use_chat_history"), true);
        emitProgress(progressListener, "controls_loaded", "已读取全局控制开关。",
                Map.of(
                        "use_saved_memories", useSavedMemories,
                        "use_chat_history", useChatHistory
                ));

        // 1) 获取最近 10 轮完整对话作为 messages 上下文
        long contextStartNs = System.nanoTime();
        List<Map<String, Object>> recentTurns = useChatHistory ?
            storage.getRecentConversationTurns(RECENT_TURNS_FOR_MESSAGES) : new ArrayList<>();
        long contextMs = elapsedMillis(contextStartNs);
        emitProgress(progressListener, "context_loaded", "已加载对话上下文。",
                Map.of("recent_turns", recentTurns.size()));

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
        String startupMap = consumeStartupMapOnce();
        String response;
        // 仅允许结构化显式授权，不基于自然语言关键词放行命令执行。
        boolean commandExecutionAllowed = false;
        try (ToolRuntimeContext.Scope ignored = ToolRuntimeContext.bindTaskSourceContext(
                sourcePlatform,
                sourceConversationId,
                sourceSenderId,
                commandExecutionAllowed
        )) {
            response = generateResponse(
                    userMessage,
                    useSavedMemories,
                    useChatHistory,
                    messages,
                    startupMap,
                    dueTaskNotifications,
                    sourcePlatform,
                    sourceConversationId,
                    progressListener
            );
        }

        // 3) 后处理全部改为异步提交：主链路优先返回，后台尽力执行。
        long postprocessSubmitStartNs = System.nanoTime();
        int submittedAsyncTasks = 0;
        boolean shouldGenerateTurnSummary = useChatHistory
                && conversationSummaryService != null
                && conversationSummaryService.onTurnCompleted();
        String topicShiftContext = buildRecentContextSummary(messages);
        submittedAsyncTasks += submitPostProcessTasks(
                useSavedMemories,
                useChatHistory,
                shouldGenerateTurnSummary,
                topicShiftContext,
                userMessage,
                response,
                timestamp,
                sourcePlatform,
                sourceConversationId,
                sourceSenderId
        );
        long postprocessSubmitMs = elapsedMillis(postprocessSubmitStartNs);
        emitProgress(progressListener, "memory_postprocess_queued", "记忆后处理已异步排队。",
                Map.of("submitted_tasks", submittedAsyncTasks));

        emitProgress(progressListener, "completed", "已完成回答生成。");
        long totalMs = elapsedMillis(requestStartNs);
        log.info("Conversation RTT(sync): total={}ms, context={}ms, postprocess_submit={}ms, async_tasks_submitted={}",
                totalMs, contextMs, postprocessSubmitMs, submittedAsyncTasks);
        return decorateResponseWithTaskSignals(response, dueTaskNotifications);
    }

    // ========== 内部方法 ==========

    private String handleTemporaryConversation(String userMessage) {
        return handleTemporaryConversation(userMessage, null);
    }

    private String handleTemporaryConversation(String userMessage, ConversationProgressListener progressListener) {
        String systemPrompt = promptBuilder.buildTemporaryPrompt();
        List<ChatMessage> messages = List.of(new UserMessage(userMessage));
        emitProgress(progressListener, "generating", "正在调用模型生成回复。");
        return llmClient.chat(systemPrompt, messages, 0.7);
    }

    private void handleExplicitMemory(Map<String, Object> memoryData, String rawUserMessage) {
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
            String existingContent = existing == null || existing.getContent() == null
                    ? ""
                    : existing.getContent().trim();
            String newContent = content.trim();
            if (Objects.equals(existingContent, newContent)) {
                log.info("Explicit memory unchanged, skip conflict: {}", slotName);
                return;
            }
            Map<String, Object> record = new HashMap<>();
            record.put("slot_name", slotName);
            record.put("new_content", content);
            record.put("existing_content", existing == null ? null : existing.getContent());
            record.put("memory_type", memoryType);
            record.put("source", Memory.SourceType.EXPLICIT.name());
            record.put("status", Memory.MemoryStatus.CONFLICT.name());
            record.put("detected_at", LocalDateTime.now().toString());
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

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        Boolean parsed = parseBooleanText(String.valueOf(value));
        if (parsed != null) {
            return parsed;
        }
        return defaultValue;
    }

    private Boolean parseOptionalBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return parseBooleanText(String.valueOf(value));
    }

    private Boolean parseBooleanText(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        if ("true".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text)
                || "1".equals(text)
                || "yes".equalsIgnoreCase(text)
                || "y".equalsIgnoreCase(text)
                || "是".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)
                || "off".equalsIgnoreCase(text)
                || "0".equals(text)
                || "no".equalsIgnoreCase(text)
                || "n".equalsIgnoreCase(text)
                || "否".equals(text)) {
            return false;
        }
        return null;
    }

    private String consumeStartupMapOnce() {
        if (!startupMapInjected.compareAndSet(false, true)) {
            return "";
        }
        return agentGuideService.getCachedGuide();
    }

    private String startupMapForEval() {
        return agentGuideService.getCachedGuide();
    }

    private String generateResponse(String currentMessage,
                                    boolean useSavedMemories,
                                    boolean useChatHistory,
                                    List<ChatMessage> messages,
                                    String startupMap,
                                    List<Map<String, Object>> dueTaskNotifications,
                                    String sourcePlatform,
                                    String sourceConversationId,
                                    ConversationProgressListener progressListener) {
        long reflectionMs = 0L;
        long evidenceMs = 0L;
        long llmMs = 0L;
        // Memory Reflection：在加载记忆前先判断是否需要
        ReflectionResult reflection = useSavedMemories
                ? ReflectionResult.fallback()
                : ReflectionResult.memoryDisabled();
        if (useSavedMemories) {
            emitProgress(progressListener, "reflection_started", "正在判断是否需要加载长期记忆。");
            long reflectionStartNs = System.nanoTime();
            try {
                if (memoryReflectionService != null) {
                    String recentContext = buildRecentContextSummary(messages);
                    reflection = memoryReflectionService.reflect(currentMessage, recentContext);
                } else {
                    log.warn("MemoryReflectionService unavailable, using fallback reflection result");
                }
            } catch (Exception e) {
                log.warn("Memory reflection invocation failed, using fallback", e);
            }
            reflection = ensureReflectionResult(reflection, true, "normal");
            reflection = normalizeRuntimeReflection(reflection, true);
            reflectionMs = elapsedMillis(reflectionStartNs);
            emitProgress(progressListener, "reflection_done", "记忆需求判断完成。",
                    Map.of("needs_memory", reflection.needs_memory()));
        } else {
            reflection = ensureReflectionResult(reflection, false, "normal");
            reflection = normalizeRuntimeReflection(reflection, false);
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
        long evidenceStartNs = System.nanoTime();
        String systemPrompt = buildSystemPromptWithEvidence(
                useSavedMemories,
                useChatHistory,
                currentMessage,
                reflection,
                dueTaskNotifications,
                sourcePlatform,
                sourceConversationId,
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
        evidenceMs = elapsedMillis(evidenceStartNs);
        emitProgress(progressListener, "evidence_ready", "记忆证据准备完成。",
                Map.of(
                        "retrieved_insights", evidence.snapshotRetrievedInsights().size(),
                        "retrieved_examples", evidence.snapshotRetrievedExamples().size(),
                        "loaded_skills", evidence.snapshotLoadedSkills().size(),
                        "retrieved_tasks", evidence.snapshotRetrievedTasks().size()
                ));

        List<LlmClient.ToolDefinition> tracedTools = attachToolUsageTracking(tools, evidence);
        emitProgress(progressListener, "generating", "正在调用模型生成回复。");
        long llmStartNs = System.nanoTime();
        String response = llmClient.chatWithTools(systemPrompt, messages, tracedTools, 0.7);
        llmMs = elapsedMillis(llmStartNs);
        emitProgress(progressListener, "generated", "模型回复已生成。");
        evidence.finalizeUsedEvidence(reflection, shouldLoadMemory);

        String truncatedMessage = currentMessage.length() > 200
                ? currentMessage.substring(0, 200) + "..."
                : currentMessage;
        MemoryEvidenceTrace trace = new MemoryEvidenceTrace(
                LocalDateTime.now(),
                truncatedMessage,
                reflection,
                shouldLoadMemory,
                evidence.snapshotRetrievedInsights(),
                evidence.snapshotUsedInsights(),
                evidence.snapshotRetrievedExamples(),
                evidence.snapshotUsedExamples(),
                evidence.snapshotLoadedSkills(),
                evidence.snapshotUsedSkills(),
                evidence.snapshotRetrievedTasks(),
                evidence.snapshotUsedTasks(),
                evidence.usedEvidenceSummary
        );
        this.lastEvidenceTrace = trace;

        log.info("Memory Evidence Trace: loaded={}, retrieved(insights/examples/skills/tasks)=({}/{}/{}/{}), used=({}/{}/{}/{})",
                shouldLoadMemory,
                trace.retrievedInsights().size(),
                trace.retrievedExamples().size(),
                trace.loadedSkills().size(),
                trace.retrievedTasks().size(),
                trace.usedInsights().size(),
                trace.usedExamples().size(),
                trace.usedSkills().size(),
                trace.usedTasks().size());
        log.info("Conversation stages(sync): reflection={}ms, evidence={}ms, llm={}ms",
                reflectionMs, evidenceMs, llmMs);

        return response;
    }

    private void emitProgress(ConversationProgressListener listener, String stage, String message) {
        emitProgress(listener, stage, message, Map.of());
    }

    private void emitProgress(ConversationProgressListener listener,
                              String stage,
                              String message,
                              Map<String, Object> details) {
        if (listener == null) {
            return;
        }
        try {
            listener.onEvent(new ConversationProgressEvent(
                    stage == null ? "" : stage,
                    message == null ? "" : message,
                    details == null ? Map.of() : Map.copyOf(details),
                    System.currentTimeMillis()
            ));
        } catch (Exception e) {
            log.debug("Progress listener failed: {}", e.getMessage());
        }
    }

    /**
     * 评测专用回答生成：保留记忆反思与记忆检索，但禁用工具调用，避免副作用。
     */
    private String generateResponseForEvalWithoutTools(String currentMessage,
                                                       boolean useSavedMemories,
                                                       boolean useChatHistory,
                                                       List<ChatMessage> messages,
                                                       String startupMap) {
        ReflectionResult reflection = useSavedMemories
                ? ReflectionResult.fallback()
                : ReflectionResult.memoryDisabled();
        if (useSavedMemories) {
            try {
                if (memoryReflectionService != null) {
                    String recentContext = buildRecentContextSummary(messages);
                    reflection = memoryReflectionService.reflect(currentMessage, recentContext);
                } else {
                    log.warn("MemoryReflectionService unavailable in eval mode, using fallback reflection result");
                }
            } catch (Exception e) {
                log.warn("Memory reflection invocation failed in eval mode, using fallback", e);
            }
            reflection = ensureReflectionResult(reflection, true, "eval");
            reflection = normalizeRuntimeReflection(reflection, true);
        } else {
            reflection = ensureReflectionResult(reflection, false, "eval");
            reflection = normalizeRuntimeReflection(reflection, false);
        }
        this.lastReflectionResult = reflection;

        boolean shouldLoadMemory = useSavedMemories && reflection.needs_memory();
        List<String> availableSkillNames = shouldLoadMemory ? skillService.listSkillNames() : List.of();
        EvidenceCollector evidence = new EvidenceCollector();

        String systemPrompt = buildSystemPromptWithEvidence(
                useSavedMemories,
                useChatHistory,
                currentMessage,
                reflection,
                List.of(),
                "",
                "",
                startupMap,
                availableSkillNames,
                false,
                false,
                false,
                false,
                false,
                false,
                evidence
        );
        evidence.finalizeUsedEvidence(reflection, shouldLoadMemory);

        String truncatedMessage = currentMessage.length() > 200
                ? currentMessage.substring(0, 200) + "..."
                : currentMessage;
        MemoryEvidenceTrace trace = new MemoryEvidenceTrace(
                LocalDateTime.now(),
                truncatedMessage,
                reflection,
                shouldLoadMemory,
                evidence.snapshotRetrievedInsights(),
                evidence.snapshotUsedInsights(),
                evidence.snapshotRetrievedExamples(),
                evidence.snapshotUsedExamples(),
                evidence.snapshotLoadedSkills(),
                evidence.snapshotUsedSkills(),
                evidence.snapshotRetrievedTasks(),
                evidence.snapshotUsedTasks(),
                evidence.usedEvidenceSummary
        );
        this.lastEvidenceTrace = trace;

        return llmClient.chat(systemPrompt, messages, 0.7);
    }

    private ReflectionResult ensureReflectionResult(ReflectionResult reflection,
                                                    boolean useSavedMemories,
                                                    String mode) {
        if (reflection != null) {
            return reflection;
        }
        if (useSavedMemories) {
            log.warn("Memory reflection returned null in {} mode, using fallback reflection result", mode);
            return ReflectionResult.fallback();
        }
        return ReflectionResult.memoryDisabled();
    }

    private ReflectionResult normalizeRuntimeReflection(ReflectionResult reflection, boolean useSavedMemories) {
        ReflectionResult source = reflection;
        if (source == null) {
            source = useSavedMemories ? ReflectionResult.fallback() : ReflectionResult.memoryDisabled();
        }
        if (!useSavedMemories) {
            return ReflectionResult.memoryDisabled();
        }
        boolean needsMemory = source.needs_memory();
        String memoryPurpose = normalizeMemoryPurpose(source.memory_purpose(), needsMemory);
        String reason = normalizeReason(source.reason(), needsMemory);
        double confidence = normalizeConfidence(source.confidence(), 0.7d);
        String retrievalHint = normalizeRetrievalHint(source.retrieval_hint(), needsMemory);
        List<String> evidenceTypes = normalizeEvidenceTypes(source.evidence_types(), needsMemory, memoryPurpose);
        List<String> evidencePurposes = normalizeEvidencePurposes(source.evidence_purposes(), needsMemory, memoryPurpose);
        return new ReflectionResult(
                needsMemory,
                memoryPurpose,
                reason,
                confidence,
                retrievalHint,
                evidenceTypes,
                evidencePurposes
        );
    }

    /** 内部辅助类，用于在构建 system prompt 时收集证据使用数据 */
    private static class EvidenceCollector {
        private static final Set<String> INSIGHT_PURPOSES = Set.of("personalization", "continuity", "constraint");

        private final LinkedHashSet<String> retrievedInsights = new LinkedHashSet<>();
        private final LinkedHashSet<String> retrievedExamples = new LinkedHashSet<>();
        private final LinkedHashSet<String> loadedSkills = new LinkedHashSet<>();
        private final LinkedHashSet<String> retrievedTasks = new LinkedHashSet<>();
        private final LinkedHashSet<String> usedInsights = new LinkedHashSet<>();
        private final LinkedHashSet<String> usedExamples = new LinkedHashSet<>();
        private final LinkedHashSet<String> usedSkills = new LinkedHashSet<>();
        private final LinkedHashSet<String> usedTasks = new LinkedHashSet<>();
        private String usedEvidenceSummary = "本轮未形成证据摘要。";

        void addRetrievedInsights(Collection<String> items) {
            if (items != null) {
                retrievedInsights.addAll(items.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).toList());
            }
        }

        void addRetrievedExamples(Collection<String> items) {
            if (items != null) {
                retrievedExamples.addAll(items.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).toList());
            }
        }

        void addLoadedSkills(Collection<String> items) {
            if (items != null) {
                loadedSkills.addAll(items.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).toList());
            }
        }

        void addRetrievedTasks(Collection<String> items) {
            if (items != null) {
                retrievedTasks.addAll(items.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).toList());
            }
        }

        void recordToolUsage(String toolName, String argumentsJson) {
            if ("load_skill".equals(toolName)) {
                String skillName = extractJsonString(argumentsJson, "name");
                if (skillName != null && !skillName.isBlank()) {
                    usedSkills.add(skillName.trim());
                } else {
                    usedSkills.add("load_skill");
                }
                return;
            }
            if ("create_task".equals(toolName)) {
                String title = extractJsonString(argumentsJson, "title");
                if (title == null || title.isBlank()) {
                    title = extractJsonString(argumentsJson, "task_title");
                }
                if (title == null || title.isBlank()) {
                    title = "create_task";
                }
                usedTasks.add(title.trim());
            }
        }

        void finalizeUsedEvidence(ReflectionResult reflection, boolean memoryLoaded) {
            if (!memoryLoaded) {
                usedEvidenceSummary = "反思判断不需要记忆，已跳过长期证据加载。";
                return;
            }
            Set<String> purposes = reflection == null || reflection.evidence_purposes() == null
                    ? Set.of()
                    : reflection.evidence_purposes().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            if (!Collections.disjoint(purposes, INSIGHT_PURPOSES)) {
                usedInsights.addAll(limit(retrievedInsights, 5));
            }
            if (purposes.contains("experience")) {
                usedExamples.addAll(limit(retrievedExamples, 3));
            }
            if (purposes.contains("followup")) {
                usedTasks.addAll(limit(retrievedTasks, 3));
            }

            List<String> summaryParts = new ArrayList<>();
            summaryParts.add("insights " + usedInsights.size() + "/" + retrievedInsights.size());
            summaryParts.add("examples " + usedExamples.size() + "/" + retrievedExamples.size());
            summaryParts.add("skills " + usedSkills.size() + "/" + loadedSkills.size());
            summaryParts.add("tasks " + usedTasks.size() + "/" + retrievedTasks.size());
            usedEvidenceSummary = String.join(", ", summaryParts);
        }

        private static List<String> limit(LinkedHashSet<String> source, int maxSize) {
            if (source.isEmpty()) {
                return List.of();
            }
            return source.stream().limit(Math.max(0, maxSize)).toList();
        }

        List<String> snapshotRetrievedInsights() {
            return List.copyOf(retrievedInsights);
        }

        List<String> snapshotRetrievedExamples() {
            return List.copyOf(retrievedExamples);
        }

        List<String> snapshotLoadedSkills() {
            return List.copyOf(loadedSkills);
        }

        List<String> snapshotRetrievedTasks() {
            return List.copyOf(retrievedTasks);
        }

        List<String> snapshotUsedInsights() {
            return List.copyOf(usedInsights);
        }

        List<String> snapshotUsedExamples() {
            return List.copyOf(usedExamples);
        }

        List<String> snapshotUsedSkills() {
            return List.copyOf(usedSkills);
        }

        List<String> snapshotUsedTasks() {
            return List.copyOf(usedTasks);
        }
    }

    @SuppressWarnings("unchecked")
    private String buildSystemPromptWithEvidence(boolean useSavedMemories,
                                     boolean useChatHistory,
                                     String currentMessage,
                                     ReflectionResult reflection,
                                     List<Map<String, Object>> dueTaskNotifications,
                                     String sourcePlatform,
                                     String sourceConversationId,
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

        boolean shouldLoadMemory = useSavedMemories && reflection != null && reflection.needs_memory();
        Set<String> purposes = normalizePurposes(reflection);
        Set<String> evidenceTypes = normalizeEvidenceTypes(reflection);
        boolean needsInsightEvidence = shouldLoadMemory && needsInsightEvidence(purposes, evidenceTypes);
        boolean needsExperienceEvidence = shouldLoadMemory
                && (purposes.contains("experience") || evidenceTypes.contains("EXAMPLE"));
        boolean needsFollowupEvidence = shouldLoadMemory
                && (purposes.contains("followup") || evidenceTypes.contains("TASK"));

        List<Map.Entry<String, Memory>> topMemories = needsInsightEvidence
                ? memoryManager.getTopOfMindMemories(topOfMindLimit)
                : new ArrayList<>();

        String userInsightsNarrative = needsInsightEvidence
                ? truncateForEvidence(storage.readUserInsightsNarrative(), BASE_PROFILE_MAX_CHARS)
                : null;

        // Phase 8: 读取会话摘要，用于替代 olderUserMessages 压缩 prompt
        String sessionSummariesText = null;
        if (useChatHistory && needsInsightEvidence && conversationSummaryService != null) {
            try {
                List<Map<String, Object>> summaries = conversationSummaryService.getRecentSummaries(3);
                if (summaries != null && !summaries.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Map<String, Object> s : summaries) {
                        String summary = String.valueOf(s.getOrDefault("summary", ""));
                        String timeRange = String.valueOf(s.getOrDefault("time_range", "unknown"));
                        Object topicsObj = s.get("key_topics");
                        String topics = "";
                        if (topicsObj instanceof List<?> topicList) {
                            topics = topicList.stream()
                                    .map(String::valueOf)
                                    .collect(Collectors.joining(", "));
                        }
                        sb.append("**[").append(timeRange).append("]** ");
                        sb.append(summary);
                        if (!topics.isEmpty()) {
                            sb.append(" (话题: ").append(topics).append(")");
                        }
                        sb.append("\n\n");
                    }
                    sessionSummariesText = sb.toString().trim();
                }
            } catch (Exception e) {
                log.warn("Failed to read session summaries for prompt compression, skipping", e);
            }
        }

        // 仅在无摘要时读取 older messages，避免先读后丢。
        List<Map<String, Object>> olderUserMessages = (useChatHistory && (sessionSummariesText == null || sessionSummariesText.isBlank()))
                ? storage.getOlderUserMessages(RECENT_TURNS_FOR_MESSAGES, recentMessagesLimit)
                : new ArrayList<>();

        // RAG 语义检索
        List<RagService.RelevantMemory> ragContext = null;
        if (ragEnabled && needsInsightEvidence && currentMessage != null && !currentMessage.isEmpty()) {
            try {
                int ragLimit = Math.max(1, Math.min(ragMaxResults, FAST_RAG_MAX_RESULTS));
                ragContext = ragService.buildSmartContext(currentMessage, ragLimit);
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
        List<String> retrievedExamples = new ArrayList<>();
        if (ragEnabled && needsExperienceEvidence && currentMessage != null && !currentMessage.isEmpty()) {
            try {
                List<RagService.RelevantMemory> examples = ragService.searchExamples(currentMessage, FAST_EXAMPLE_MAX_RESULTS, 0.3);
                if (!examples.isEmpty()) {
                    StringBuilder eb = new StringBuilder();
                    for (RagService.RelevantMemory ex : examples) {
                        String problem = (String) ex.getMetadata().getOrDefault("problem", "");
                        String solution = (String) ex.getMetadata().getOrDefault("solution", "");
                        if (!problem.isBlank()) {
                            retrievedExamples.add(truncateForEvidence(problem, 80));
                        }
                        eb.append("**Problem**: ").append(problem).append("\n");
                        eb.append("**Solution**: ").append(solution).append("\n\n");
                    }
                    examplesContent = eb.toString();
                }
            } catch (Exception e) {
                log.warn("Example search failed", e);
            }
        }

        boolean includeMatchedTasks = needsFollowupEvidence || (dueTaskNotifications != null && !dueTaskNotifications.isEmpty());
        List<String> retrievedTasks = collectTaskContext(sourcePlatform, sourceConversationId, dueTaskNotifications, includeMatchedTasks);

        // 收集证据使用数据
        if (evidence != null) {
            List<String> insightEvidence = new ArrayList<>();
            for (Map.Entry<String, Memory> entry : topMemories) {
                String slot = entry.getKey();
                String content = entry.getValue() == null ? "" : String.valueOf(entry.getValue().getContent());
                insightEvidence.add(slot + ": " + truncateForEvidence(content, 80));
            }
            if (ragContext != null) {
                for (RagService.RelevantMemory memory : ragContext) {
                    String score = String.format(Locale.ROOT, "%.0f%%", memory.getScore() * 100);
                    insightEvidence.add(memory.getSlotName() + " (" + score + "): " + truncateForEvidence(memory.getContent(), 80));
                }
            }
            if (userInsightsNarrative != null && !userInsightsNarrative.isBlank()) {
                insightEvidence.add("user_insights.md: " + truncateForEvidence(userInsightsNarrative, 80));
            }
            evidence.addRetrievedInsights(insightEvidence);
            evidence.addRetrievedExamples(retrievedExamples);
            evidence.addLoadedSkills(availableSkillNames);
            evidence.addRetrievedTasks(retrievedTasks);
        }

        return promptBuilder.buildSystemPrompt(
            startupMap,
            userMetadata,
            assistantPreferences,
            reflection,
            olderUserMessages,
            userInsightsNarrative,
            availableSkillNames,
            skillToolAvailable,
            ragToolAvailable,
            shellToolAvailable,
            shellCommandToolAvailable,
            pythonToolAvailable,
            taskToolAvailable,
            retrievedTasks,
            examplesContent,
            ragContext,
            sessionSummariesText
        );
    }

    private List<LlmClient.ToolDefinition> attachToolUsageTracking(List<LlmClient.ToolDefinition> tools,
                                                                    EvidenceCollector evidence) {
        if (tools == null || tools.isEmpty() || evidence == null) {
            return tools == null ? List.of() : tools;
        }
        return tools.stream()
                .map(tool -> new LlmClient.ToolDefinition(
                        tool.specification(),
                        request -> {
                            evidence.recordToolUsage(request.name(), request.arguments());
                            return tool.executor().apply(request);
                        }))
                .toList();
    }

    private Set<String> normalizePurposes(ReflectionResult reflection) {
        if (reflection == null || reflection.evidence_purposes() == null) {
            return Set.of();
        }
        return reflection.evidence_purposes().stream()
                .map(ReflectionResult::normalizeEvidencePurpose)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> normalizeEvidenceTypes(ReflectionResult reflection) {
        if (reflection == null || reflection.evidence_types() == null) {
            return Set.of();
        }
        return reflection.evidence_types().stream()
                .map(ReflectionResult::normalizeEvidenceType)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean needsInsightEvidence(Set<String> purposes, Set<String> evidenceTypes) {
        boolean purposeNeedsInsight = purposes != null && (
                purposes.contains("personalization")
                        || purposes.contains("continuity")
                        || purposes.contains("constraint")
        );
        boolean typeNeedsInsight = evidenceTypes != null && (
                evidenceTypes.contains("USER_INSIGHT")
                        || evidenceTypes.contains("SESSION_SUMMARY")
                        || evidenceTypes.contains("RECENT_HISTORY")
        );
        return purposeNeedsInsight || typeNeedsInsight;
    }

    private List<String> collectTaskContext(String sourcePlatform,
                                            String sourceConversationId,
                                            List<Map<String, Object>> dueTaskNotifications,
                                            boolean includeMatchedTasks) {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (dueTaskNotifications != null) {
            for (Map<String, Object> notification : dueTaskNotifications) {
                String title = String.valueOf(notification.getOrDefault("task_title", "未命名任务")).trim();
                String dueRaw = String.valueOf(notification.getOrDefault("due_at", "")).trim();
                String dueAt = dueRaw.isEmpty() ? "未知时间" : formatTaskTime(dueRaw);
                items.add("[到期] " + title + " @ " + dueAt);
            }
        }
        if (!includeMatchedTasks || scheduledTaskService == null) {
            return items.stream().limit(5).toList();
        }
        try {
            List<com.memsys.task.model.ScheduledTask> tasks = scheduledTaskService.listTasks(20);
            String normalizedPlatform = safeLower(sourcePlatform);
            String normalizedConversationId = safeTrim(sourceConversationId);
            for (com.memsys.task.model.ScheduledTask task : tasks) {
                if (task == null) {
                    continue;
                }
                if (!matchesTaskConversation(task, normalizedPlatform, normalizedConversationId)) {
                    continue;
                }
                String title = task.getTitle() == null || task.getTitle().isBlank() ? "(untitled)" : task.getTitle().trim();
                String status = task.getStatus() == null || task.getStatus().isBlank() ? "unknown" : task.getStatus().trim();
                String dueAt = task.getDueAt() == null ? "unknown" : task.getDueAt().format(TASK_TIME_FORMATTER);
                items.add("[匹配] " + title + " (" + status + ", " + dueAt + ")");
                if (items.size() >= 5) {
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("Task context collection skipped: {}", e.getMessage());
        }
        return items.stream().limit(5).toList();
    }

    private boolean matchesTaskConversation(com.memsys.task.model.ScheduledTask task,
                                            String normalizedPlatform,
                                            String normalizedConversationId) {
        if (task == null) {
            return false;
        }
        String taskConversationId = safeTrim(task.getSourceConversationId());
        if (normalizedConversationId.isBlank() || taskConversationId.isBlank()) {
            return false;
        }
        if (!normalizedConversationId.equals(taskConversationId)) {
            return false;
        }
        String taskPlatform = safeLower(task.getSourcePlatform());
        return normalizedPlatform.isBlank() || taskPlatform.isBlank() || normalizedPlatform.equals(taskPlatform);
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeLower(String value) {
        return safeTrim(value).toLowerCase(Locale.ROOT);
    }

    private String truncateForEvidence(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\n", " ").trim();
        if (normalized.length() <= Math.max(0, maxLen)) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen)) + "...";
    }

    private static String extractJsonString(String json, String field) {
        if (json == null || json.isBlank() || field == null || field.isBlank()) {
            return null;
        }
        String pattern = "\"" + field + "\"";
        int fieldIndex = json.indexOf(pattern);
        if (fieldIndex < 0) {
            return null;
        }
        int colon = json.indexOf(':', fieldIndex + pattern.length());
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        int end = start + 1;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"' && json.charAt(end - 1) != '\\') {
                break;
            }
            end++;
        }
        if (end >= json.length()) {
            return null;
        }
        return json.substring(start + 1, end);
    }

    private int submitPostProcessTasks(boolean useSavedMemories,
                                       boolean useChatHistory,
                                       boolean shouldGenerateTurnSummary,
                                       String topicShiftContext,
                                       String userMessage,
                                       String response,
                                       LocalDateTime timestamp,
                                       String sourcePlatform,
                                       String sourceConversationId,
                                       String sourceSenderId) {
        int submitted = 0;

        if (useChatHistory) {
            submitted += submitAsyncTask("persist_conversation_history", () -> {
                storage.updateRecentMessages(userMessage, timestamp, recentMessagesLimit);
                storage.appendToHistory("user", userMessage, timestamp);
                storage.appendToHistory("assistant", response, LocalDateTime.now());
            });
        }

        MemoryEvidenceTrace trace = this.lastEvidenceTrace;
        if (trace != null) {
            submitted += submitAsyncTask("append_memory_evidence_trace", () -> appendEvidenceTrace(trace));
        }

        if (useSavedMemories && memoryExtractor != null) {
            submitted += submitAsyncTask("extract_explicit_memory", () -> {
                try {
                    Map<String, Object> explicitMemory = memoryExtractor.extractExplicitMemory(userMessage);
                    if (parseBoolean(explicitMemory.get("has_memory"), false)) {
                        handleExplicitMemory(explicitMemory, userMessage);
                    }
                } catch (Exception e) {
                    log.debug("Explicit memory extraction skipped: {}", e.getMessage());
                }
            });
            submitted += submitAsyncTask("update_memory_access", () -> updateMemoryAccess(userMessage));
        }

        if (useChatHistory && conversationSummaryService != null && shouldGenerateTurnSummary) {
            submitted += submitAsyncTask("conversation_turn_summary", () -> {
                try {
                    String summary = conversationSummaryService.generateAndPersistSummary();
                    if (summary != null) {
                        log.info("Session summary generated ({} turns)", conversationSummaryService.getCurrentTurnCount());
                    }
                } catch (Exception e) {
                    log.debug("Session summary generation skipped: {}", e.getMessage());
                }
            });
        }

        if (useChatHistory && conversationSummaryService != null) {
            submitted += submitAsyncTask("topic_shift_summary", () -> {
                try {
                    String summary = conversationSummaryService.checkTopicShiftAndSummarize(
                            topicShiftContext, userMessage);
                    if (summary != null) {
                        log.info("Topic-shift summary generated at turn {}",
                                conversationSummaryService.getCurrentTurnCount());
                    }
                } catch (Exception e) {
                    log.debug("Topic-shift summary skipped: {}", e.getMessage());
                }
            });
        }

        if (scheduledTaskService != null) {
            submitted += submitAsyncTask("extract_scheduled_task", () -> {
                try {
                    var taskOpt = scheduledTaskService.tryCreateTaskFromMessage(
                            userMessage, sourcePlatform, sourceConversationId, sourceSenderId);
                    taskOpt.ifPresent(task ->
                            log.info("Auto-created scheduled task from conversation: '{}' due at {}",
                                    task.getTitle(), task.getDueAt()));
                } catch (Exception e) {
                    log.debug("Scheduled task extraction skipped: {}", e.getMessage());
                }
            });
        }

        return submitted;
    }

    private int submitAsyncTask(String taskName, Runnable task) {
        if (task == null) {
            return 0;
        }
        if (memoryAsync == null) {
            try {
                task.run();
            } catch (Exception e) {
                log.debug("Inline postprocess task failed: {}", taskName, e);
            }
            return 1;
        }
        boolean accepted = memoryAsync.submit(taskName, task);
        return accepted ? 1 : 0;
    }

    private long elapsedMillis(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private void appendEvidenceTrace(MemoryEvidenceTrace trace) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("timestamp", trace.timestamp() == null ? null : trace.timestamp().toString());
            record.put("user_message", trace.userMessage());
            record.put("memory_loaded", trace.memoryLoaded());

            ReflectionResult reflection = trace.reflection();
            Map<String, Object> reflectionMap = new LinkedHashMap<>();
            if (reflection != null) {
                reflectionMap.put("needs_memory", reflection.needs_memory());
                reflectionMap.put("memory_purpose", reflection.memory_purpose());
                reflectionMap.put("reason", reflection.reason());
                reflectionMap.put("confidence", reflection.confidence());
                reflectionMap.put("retrieval_hint", reflection.retrieval_hint());
                reflectionMap.put("evidence_types", reflection.evidence_types());
                reflectionMap.put("evidence_purposes", reflection.evidence_purposes());
            }
            record.put("reflection", reflectionMap);
            record.put("retrieved_insights", trace.retrievedInsights());
            record.put("used_insights", trace.usedInsights());
            record.put("retrieved_examples", trace.retrievedExamples());
            record.put("used_examples", trace.usedExamples());
            record.put("loaded_skills", trace.loadedSkills());
            record.put("used_skills", trace.usedSkills());
            record.put("retrieved_tasks", trace.retrievedTasks());
            record.put("used_tasks", trace.usedTasks());
            record.put("used_evidence_summary", trace.usedEvidenceSummary());
            storage.appendMemoryEvidenceTrace(record);
        } catch (Exception e) {
            log.warn("Failed to append memory evidence trace", e);
        }
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
        if (memory == null || memory.getContent() == null || memory.getContent().isBlank()) {
            return false;
        }
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
        if (lastEvidenceTrace != null) {
            return lastEvidenceTrace;
        }
        try {
            List<Map<String, Object>> traces = storage.readMemoryEvidenceTraces(1);
            if (traces.isEmpty()) {
                return null;
            }
            return parseEvidenceTrace(traces.get(0));
        } catch (Exception e) {
            log.debug("Failed to load last evidence trace from storage: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取最近 N 轮的 Memory Evidence Trace（按时间顺序），供 /memory-debug [N] 使用。
     * 解析失败的记录会被跳过；结果遵循“内存态优先 + 持久化补齐”。
     */
    public List<MemoryEvidenceTrace> getRecentEvidenceTraces(int limit) {
        int effectiveLimit = limit <= 0 ? 1 : limit;
        List<MemoryEvidenceTrace> traces = new ArrayList<>();
        try {
            List<Map<String, Object>> records = storage.readMemoryEvidenceTraces(effectiveLimit + 1);
            traces = records.stream()
                    .map(this::parseEvidenceTrace)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (Exception e) {
            log.debug("Failed to load recent evidence traces from storage: {}", e.getMessage());
        }

        MemoryEvidenceTrace inMemoryTrace = lastEvidenceTrace;
        if (inMemoryTrace != null) {
            boolean alreadyPersisted = traces.stream().anyMatch(trace -> sameTrace(trace, inMemoryTrace));
            if (!alreadyPersisted) {
                traces.add(inMemoryTrace);
            }
        }

        if (traces.size() > effectiveLimit) {
            return traces.subList(traces.size() - effectiveLimit, traces.size());
        }

        return traces;
    }

    private boolean sameTrace(MemoryEvidenceTrace left, MemoryEvidenceTrace right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(left.timestamp(), right.timestamp())
                && Objects.equals(left.userMessage(), right.userMessage())
                && Objects.equals(left.memoryLoaded(), right.memoryLoaded())
                && sameReflection(left.reflection(), right.reflection())
                && Objects.equals(left.retrievedInsights(), right.retrievedInsights())
                && Objects.equals(left.usedInsights(), right.usedInsights())
                && Objects.equals(left.retrievedExamples(), right.retrievedExamples())
                && Objects.equals(left.usedExamples(), right.usedExamples())
                && Objects.equals(left.loadedSkills(), right.loadedSkills())
                && Objects.equals(left.usedSkills(), right.usedSkills())
                && Objects.equals(left.retrievedTasks(), right.retrievedTasks())
                && Objects.equals(left.usedTasks(), right.usedTasks())
                && Objects.equals(left.usedEvidenceSummary(), right.usedEvidenceSummary());
    }

    private boolean sameReflection(ReflectionResult left, ReflectionResult right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return left.needs_memory() == right.needs_memory()
                && Objects.equals(left.memory_purpose(), right.memory_purpose())
                && Objects.equals(left.reason(), right.reason())
                && Double.compare(left.confidence(), right.confidence()) == 0
                && Objects.equals(left.retrieval_hint(), right.retrieval_hint())
                && Objects.equals(left.evidence_types(), right.evidence_types())
                && Objects.equals(left.evidence_purposes(), right.evidence_purposes());
    }

    @SuppressWarnings("unchecked")
    private MemoryEvidenceTrace parseEvidenceTrace(Map<String, Object> record) {
        if (record == null || record.isEmpty()) {
            return null;
        }
        LocalDateTime timestamp = parseTraceTimestamp(readFirstNonNull(record, "timestamp", "timeStamp"));

        ReflectionResult reflection = parseReflection(record);

        return new MemoryEvidenceTrace(
                timestamp,
                normalizeText(readFirstNonNull(record, "user_message", "userMessage")),
                reflection,
                parseBoolean(readFirstNonNull(record, "memory_loaded", "memoryLoaded"), false),
                readTraceList(record, "retrieved", "insights", "retrieved_insights", "retrievedInsights"),
                readTraceList(record, "used", "insights", "used_insights", "usedInsights"),
                readTraceList(record, "retrieved", "examples", "retrieved_examples", "retrievedExamples"),
                readTraceList(record, "used", "examples", "used_examples", "usedExamples"),
                readTraceList(
                        record,
                        "retrieved",
                        "skills",
                        "loaded_skills",
                        "loadedSkills",
                        "retrieved_skills",
                        "retrievedSkills"),
                readTraceList(record, "used", "skills", "used_skills", "usedSkills"),
                readTraceList(record, "retrieved", "tasks", "retrieved_tasks", "retrievedTasks"),
                readTraceList(record, "used", "tasks", "used_tasks", "usedTasks"),
                normalizeText(readFirstNonNull(record, "used_evidence_summary", "usedEvidenceSummary"))
        );
    }

    private List<String> readTraceList(Map<String, Object> record,
                                       String group,
                                       String category,
                                       String... directKeys) {
        List<String> direct = normalizeStringList(readFirstNonNull(record, directKeys));
        if (!direct.isEmpty()) {
            return direct;
        }
        List<String> groupedFromTopLevel = readTraceListFromGroupedMap(record, group, category, directKeys);
        if (!groupedFromTopLevel.isEmpty()) {
            return groupedFromTopLevel;
        }
        Map<String, Object> evidence = asMap(readFirstNonNull(record, "evidence", "evidence_trace", "evidenceTrace"));
        if (evidence.isEmpty()) {
            return List.of();
        }

        List<String> directFromEvidence = normalizeStringList(readFirstNonNull(evidence, directKeys));
        if (!directFromEvidence.isEmpty()) {
            return directFromEvidence;
        }
        return readTraceListFromGroupedMap(evidence, group, category, directKeys);
    }

    private List<String> readTraceListFromGroupedMap(Map<String, Object> source,
                                                     String group,
                                                     String category,
                                                     String... directKeys) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        String categorySingular = singular(category);
        String[] groupedKeys = new String[4 + directKeys.length];
        groupedKeys[0] = category;
        groupedKeys[1] = categorySingular;
        groupedKeys[2] = category + "_list";
        groupedKeys[3] = toCamel(category) + "List";
        System.arraycopy(directKeys, 0, groupedKeys, 4, directKeys.length);
        List<String> groupCandidates = new ArrayList<>();
        groupCandidates.add(group);
        groupCandidates.add(toCamel(group));
        if ("retrieved".equals(group)) {
            groupCandidates.add("loaded");
        }
        for (String candidateGroup : groupCandidates) {
            if (candidateGroup == null || candidateGroup.isBlank()) {
                continue;
            }
            Map<String, Object> grouped = asMap(readFirstNonNull(source, candidateGroup));
            if (grouped.isEmpty()) {
                continue;
            }
            List<String> values = normalizeStringList(readFirstNonNull(grouped, groupedKeys));
            if (!values.isEmpty()) {
                return values;
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        if (value instanceof String rawText) {
            String text = unwrapJsonString(rawText);
            if (text != null && text.startsWith("{") && text.endsWith("}")) {
                try {
                    return TRACE_PARSER.readValue(text, new TypeReference<Map<String, Object>>() {
                    });
                } catch (Exception ignored) {
                    return Map.of();
                }
            }
        }
        return Map.of();
    }

    private String toCamel(String snakeOrWord) {
        if (snakeOrWord == null || snakeOrWord.isBlank()) {
            return "";
        }
        String[] parts = snakeOrWord.split("_");
        if (parts.length == 0) {
            return snakeOrWord;
        }
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.toString();
    }

    private String singular(String plural) {
        if (plural == null || plural.isBlank()) {
            return "";
        }
        if (plural.endsWith("s") && plural.length() > 1) {
            return plural.substring(0, plural.length() - 1);
        }
        return plural;
    }

    private LocalDateTime parseTraceTimestamp(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            LocalDateTime parsedFromEpoch = parseEpochTimestamp(number.doubleValue());
            if (parsedFromEpoch != null) {
                return parsedFromEpoch;
            }
        }
        String text = normalizeText(value);
        if (text.isBlank()) {
            return null;
        }
        LocalDateTime parsedFromEpochText = parseEpochTimestamp(text);
        if (parsedFromEpochText != null) {
            return parsedFromEpochText;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ignored) {
            // keep trying legacy/offset formats
        }
        try {
            return OffsetDateTime.parse(text)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (Exception ignored) {
            // keep trying legacy/offset formats
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            // ignore malformed timestamp and keep null for resilient CLI display
            return null;
        }
    }

    private LocalDateTime parseEpochTimestamp(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        if (!normalized.matches("[-+]?\\d{10,19}(\\.\\d+)?")) {
            return null;
        }
        try {
            return parseEpochTimestamp(Double.parseDouble(normalized));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private LocalDateTime parseEpochTimestamp(double rawEpoch) {
        if (Double.isNaN(rawEpoch) || Double.isInfinite(rawEpoch)) {
            return null;
        }
        double abs = Math.abs(rawEpoch);
        long epochMillis;
        if (abs >= 1_000_000_000_000_000_000d) {
            epochMillis = (long) (rawEpoch / 1_000_000d);
        } else if (abs >= 1_000_000_000_000_000d) {
            epochMillis = (long) (rawEpoch / 1_000d);
        } else if (abs >= 1_000_000_000_000d) {
            epochMillis = (long) rawEpoch;
        } else if (abs >= 1_000_000_000d) {
            epochMillis = (long) (rawEpoch * 1000d);
        } else {
            return null;
        }
        try {
            return Instant.ofEpochMilli(epochMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private ReflectionResult parseReflection(Map<String, Object> record) {
        if (record == null || record.isEmpty()) {
            return null;
        }
        Object reflectionObj = readFirstNonNull(record, "reflection", "reflection_result", "reflectionResult");
        if (reflectionObj instanceof Map<?, ?> rawMap) {
            ReflectionResult nested = parseReflectionMap((Map<String, Object>) rawMap);
            if (nested != null) {
                return nested;
            }
        } else if (reflectionObj instanceof String rawText) {
            ReflectionResult nested = parseReflectionFromJsonText(rawText);
            if (nested != null) {
                return nested;
            }
        }
        // 兼容早期 trace：reflection 字段缺失，反思字段直接扁平存储在顶层。
        return parseReflectionMap(record);
    }

    private ReflectionResult parseReflectionMap(Map<String, Object> reflectionMap) {
        if (reflectionMap == null || reflectionMap.isEmpty()) {
            return null;
        }
        Boolean needsMemory = parseOptionalBoolean(readFirstNonNull(reflectionMap, "needs_memory", "needsMemory"));
        if (needsMemory == null) {
            return null;
        }
        String memoryPurpose = normalizeMemoryPurpose(
                normalizeText(readFirstNonNull(reflectionMap, "memory_purpose", "memoryPurpose")),
                needsMemory);
        String reason = normalizeText(readFirstNonNull(reflectionMap, "reason"));
        double confidence = parseOptionalDouble(readFirstNonNull(reflectionMap, "confidence"), 0.7d);
        String retrievalHint = normalizeRetrievalHint(
                normalizeText(readFirstNonNull(reflectionMap, "retrieval_hint", "retrievalHint")),
                needsMemory);
        List<String> evidenceTypes = normalizeEvidenceTypes(
                normalizeStringList(readFirstNonNull(reflectionMap, "evidence_types", "evidenceTypes")),
                needsMemory,
                memoryPurpose);
        List<String> rawPurposes = normalizeStringList(
                readFirstNonNull(reflectionMap, "evidence_purposes", "evidencePurposes"));
        if (rawPurposes.isEmpty()) {
            rawPurposes = normalizeStringList(
                    readFirstNonNull(reflectionMap, "evidence_purpose", "evidencePurpose"));
        }
        List<String> purposes = normalizeEvidencePurposes(rawPurposes, needsMemory, memoryPurpose);
        return new ReflectionResult(
                needsMemory,
                memoryPurpose,
                reason,
                confidence,
                retrievalHint,
                evidenceTypes,
                purposes
        );
    }

    private Object readFirstNonNull(Map<String, Object> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = source.get(key);
            if (value != null) {
                return value;
            }
            Object normalizedMatch = readByNormalizedKey(source, key);
            if (normalizedMatch != null) {
                return normalizedMatch;
            }
            Map<String, Object> flattenedSubtree = readFlattenedSubtree(source, key);
            if (!flattenedSubtree.isEmpty()) {
                return flattenedSubtree;
            }
        }
        return null;
    }

    private Object readByNormalizedKey(Map<String, Object> source, String key) {
        if (source == null || source.isEmpty() || key == null || key.isBlank()) {
            return null;
        }
        String normalizedKey = normalizeLookupKey(key);
        if (normalizedKey.isBlank()) {
            return null;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String entryKey = entry.getKey();
            if (entryKey == null || entryKey.isBlank()) {
                continue;
            }
            if (normalizedKey.equals(normalizeLookupKey(entryKey))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String normalizeLookupKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFlattenedSubtree(Map<String, Object> source, String key) {
        if (source == null || source.isEmpty() || key == null || key.isBlank()) {
            return Map.of();
        }
        Map<String, Object> nested = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String entryKey = entry.getKey();
            if (entryKey == null) {
                continue;
            }
            String suffix = flattenedKeySuffix(entryKey, key);
            if (suffix == null || suffix.isBlank()) {
                continue;
            }
            List<String> parts = splitFlattenedPath(suffix);
            if (parts.isEmpty()) {
                continue;
            }
            Map<String, Object> cursor = nested;
            for (int i = 0; i < parts.size() - 1; i++) {
                String part = parts.get(i);
                if (part.isBlank()) {
                    continue;
                }
                Object existing = cursor.get(part);
                if (!(existing instanceof Map<?, ?> existingMap)) {
                    Map<String, Object> created = new LinkedHashMap<>();
                    cursor.put(part, created);
                    cursor = created;
                } else {
                    cursor = (Map<String, Object>) existingMap;
                }
            }
            String leaf = parts.get(parts.size() - 1);
            if (!leaf.isBlank()) {
                cursor.put(leaf, entry.getValue());
            }
        }
        return nested;
    }

    private String flattenedKeySuffix(String entryKey, String key) {
        if (entryKey == null || entryKey.isBlank() || key == null || key.isBlank()) {
            return null;
        }
        String normalizedKey = normalizeLookupKey(key);
        if (normalizedKey.isBlank()) {
            return null;
        }
        String candidate = entryKey.trim();
        if (candidate.startsWith("#/")) {
            candidate = candidate.substring(2);
        } else if (candidate.startsWith("/")) {
            candidate = candidate.substring(1);
        }
        if (candidate.startsWith("$")) {
            if (candidate.length() == 1) {
                return null;
            }
            char next = candidate.charAt(1);
            if (next != '.' && next != '/' && next != '[') {
                return null;
            }
            candidate = candidate.substring(1);
            if (candidate.startsWith(".") || candidate.startsWith("/")) {
                candidate = candidate.substring(1);
            }
        }
        if (candidate.isBlank()) {
            return null;
        }
        int delimiterIndex = -1;
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if (ch == '.' || ch == '[' || ch == '/') {
                delimiterIndex = i;
                break;
            }
        }
        if (delimiterIndex < 0) {
            return null;
        }
        if (delimiterIndex == 0 && candidate.charAt(0) == '[') {
            int closeIdx = candidate.indexOf(']');
            if (closeIdx <= 1) {
                return null;
            }
            String root = normalizeBracketToken(candidate.substring(1, closeIdx));
            if (!normalizedKey.equals(normalizeLookupKey(root))) {
                return null;
            }
            if (closeIdx + 1 >= candidate.length()) {
                return null;
            }
            return candidate.substring(closeIdx + 1);
        }
        if (delimiterIndex == 0) {
            return null;
        }
        String root = candidate.substring(0, delimiterIndex);
        if (!normalizedKey.equals(normalizeLookupKey(root))) {
            return null;
        }
        char delimiter = candidate.charAt(delimiterIndex);
        if (delimiter == '[') {
            return candidate.substring(delimiterIndex);
        }
        return candidate.substring(delimiterIndex + 1);
    }

    private List<String> splitFlattenedPath(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < suffix.length(); i++) {
            char ch = suffix.charAt(i);
            if (ch == '.' || ch == '/') {
                if (!token.isEmpty()) {
                    parts.add(decodeJsonPointerToken(token.toString()));
                    token.setLength(0);
                }
                continue;
            }
            if (ch == '[') {
                if (!token.isEmpty()) {
                    parts.add(decodeJsonPointerToken(token.toString()));
                    token.setLength(0);
                }
                int closeIdx = suffix.indexOf(']', i + 1);
                if (closeIdx > i + 1) {
                    String bracketToken = normalizeBracketToken(suffix.substring(i + 1, closeIdx));
                    if (!bracketToken.isBlank()) {
                        parts.add(bracketToken);
                    }
                    i = closeIdx;
                }
                continue;
            }
            token.append(ch);
        }
        if (!token.isEmpty()) {
            parts.add(decodeJsonPointerToken(token.toString()));
        }
        return parts;
    }

    private String decodeJsonPointerToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        return token.replace("~1", "/").replace("~0", "~");
    }

    private String normalizeBracketToken(String token) {
        String decoded = decodeJsonPointerToken(token);
        if (decoded.isBlank()) {
            return "";
        }
        String trimmed = decoded.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private String normalizeMemoryPurpose(String memoryPurpose, boolean needsMemory) {
        return ReflectionResult.normalizeMemoryPurpose(memoryPurpose, needsMemory);
    }

    private String normalizeRetrievalHint(String retrievalHint, boolean needsMemory) {
        if (!needsMemory) {
            return "";
        }
        if (isNullLike(retrievalHint)) {
            return "优先检索与用户当前问题最相关的历史证据。";
        }
        return retrievalHint.trim();
    }

    private String normalizeReason(String reason, boolean needsMemory) {
        if (!isNullLike(reason)) {
            return reason.trim();
        }
        return needsMemory ? "需要调用长期记忆以保证回答质量。" : "当前问题可直接回答，无需调用长期记忆。";
    }

    private List<String> normalizeEvidenceTypes(List<String> evidenceTypes,
                                                boolean needsMemory,
                                                String memoryPurpose) {
        if (!needsMemory || evidenceTypes == null || evidenceTypes.isEmpty()) {
            if (needsMemory) {
                return ReflectionResult.defaultEvidenceTypesForMemoryPurpose(memoryPurpose);
            }
            return List.of();
        }
        LinkedHashSet<String> normalized = evidenceTypes.stream()
                .map(ReflectionResult::normalizeEvidenceType)
                .filter(type -> type != null && ReflectionResult.KNOWN_EVIDENCE_TYPES.contains(type))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!normalized.isEmpty()) {
            return List.copyOf(normalized);
        }
        return ReflectionResult.defaultEvidenceTypesForMemoryPurpose(memoryPurpose);
    }

    private List<String> normalizeEvidencePurposes(List<String> evidencePurposes,
                                                   boolean needsMemory,
                                                   String memoryPurpose) {
        if (!needsMemory || evidencePurposes == null || evidencePurposes.isEmpty()) {
            if (needsMemory) {
                return ReflectionResult.defaultPurposesForMemoryPurpose(memoryPurpose);
            }
            return List.of();
        }
        LinkedHashSet<String> normalized = evidencePurposes.stream()
                .map(ReflectionResult::normalizeEvidencePurpose)
                .filter(purpose -> purpose != null && ReflectionResult.KNOWN_PURPOSES.contains(purpose))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!normalized.isEmpty()) {
            return List.copyOf(normalized);
        }
        return ReflectionResult.defaultPurposesForMemoryPurpose(memoryPurpose);
    }

    private List<String> normalizeStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(this::normalizeListItem)
                    .filter(s -> !s.isBlank())
                    .toList();
        }
        if (value instanceof Map<?, ?> map) {
            return normalizeStringMapEntries(map);
        }
        String text = normalizeText(value);
        if (text.isBlank() || isNullLike(text)) {
            return List.of();
        }
        List<String> parsedFromJson = parseStringListFromJsonText(text);
        if (!parsedFromJson.isEmpty()) {
            return parsedFromJson;
        }
        List<String> parsedFromDelimitedText = parseStringListFromDelimitedText(text);
        if (!parsedFromDelimitedText.isEmpty()) {
            return parsedFromDelimitedText;
        }
        return List.of(text);
    }

    private List<String> normalizeStringMapEntries(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = normalizeListItem(entry.getKey());
            Object rawValue = entry.getValue();
            Boolean boolValue = parseOptionalBoolean(rawValue);
            if (boolValue != null) {
                if (boolValue && !key.isBlank()) {
                    items.add(key);
                }
                continue;
            }
            String normalizedValue = normalizeListItem(rawValue);
            if (!normalizedValue.isBlank()) {
                items.add(normalizedValue);
                continue;
            }
            if (!key.isBlank()) {
                items.add(key);
            }
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private ReflectionResult parseReflectionFromJsonText(String rawText) {
        String text = unwrapJsonString(rawText);
        if (text == null) {
            return null;
        }
        if (text.isBlank() || !text.startsWith("{") || !text.endsWith("}")) {
            return null;
        }
        try {
            Map<String, Object> reflectionMap = TRACE_PARSER.readValue(text, new TypeReference<Map<String, Object>>() {
            });
            return parseReflectionMap(reflectionMap);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> parseStringListFromJsonText(String rawText) {
        String text = unwrapJsonString(rawText);
        if (text == null) {
            return List.of();
        }
        if (text.isBlank() || !text.startsWith("[") || !text.endsWith("]")) {
            return List.of();
        }
        try {
            List<Object> values = TRACE_PARSER.readValue(text, new TypeReference<List<Object>>() {
            });
            return values.stream()
                    .map(this::normalizeListItem)
                    .filter(s -> !s.isBlank())
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> parseStringListFromDelimitedText(String rawText) {
        String text = normalizeText(rawText);
        if (text.isBlank()) {
            return List.of();
        }
        if (!containsListDelimiter(text)) {
            return List.of();
        }
        List<String> items = Arrays.stream(text.split(DELIMITED_LIST_SPLIT_REGEX))
                .map(this::normalizeDelimitedToken)
                .filter(s -> !s.isBlank())
                .toList();
        if (items.size() <= 1) {
            return List.of();
        }
        return items;
    }

    private boolean containsListDelimiter(String text) {
        return text.contains("\n")
                || text.contains(",")
                || text.contains("，")
                || text.contains(";")
                || text.contains("；")
                || text.contains("|");
    }

    private String normalizeDelimitedToken(String token) {
        if (token == null) {
            return "";
        }
        String stripped = token.replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "");
        return normalizeListItem(stripped);
    }

    @SuppressWarnings("unchecked")
    private String normalizeListItem(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            String candidate = normalizeText(readFirstNonNull(
                    map,
                    "name",
                    "title",
                    "text",
                    "content",
                    "id",
                    "slot_name",
                    "slotName",
                    "value"
            ));
            if (!candidate.isBlank()) {
                return isNullLike(candidate) ? "" : candidate;
            }
        }
        String normalized = normalizeText(value);
        return isNullLike(normalized) ? "" : normalized;
    }

    private String unwrapJsonString(String rawText) {
        if (rawText == null) {
            return null;
        }
        String text = rawText.trim();
        if (text.isBlank()) {
            return text;
        }
        for (int depth = 0; depth < 6; depth++) {
            if ((text.startsWith("{") && text.endsWith("}"))
                    || (text.startsWith("[") && text.endsWith("]"))) {
                return text;
            }
            if (text.length() < 2 || !text.startsWith("\"") || !text.endsWith("\"")) {
                return text;
            }
            try {
                String unwrapped = TRACE_PARSER.readValue(text, String.class);
                if (unwrapped == null) {
                    return null;
                }
                String trimmed = unwrapped.trim();
                if (trimmed.equals(text)) {
                    return trimmed;
                }
                text = trimmed;
            } catch (Exception ignored) {
                return text;
            }
        }
        return text;
    }

    private String normalizeText(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if ("null".equalsIgnoreCase(text)) {
            return "";
        }
        return text;
    }

    private boolean isNullLike(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "null".equals(normalized)
                || "undefined".equals(normalized)
                || "n/a".equals(normalized)
                || "none".equals(normalized);
    }

    private double parseOptionalDouble(Object value, double defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return normalizeConfidence(number.doubleValue(), defaultValue);
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return defaultValue;
        }
        if (text.endsWith("%") || text.endsWith("％")) {
            String numeric = text.substring(0, text.length() - 1).trim();
            if (numeric.isBlank()) {
                return defaultValue;
            }
            try {
                return normalizeConfidence(Double.parseDouble(numeric), defaultValue);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        try {
            return normalizeConfidence(Double.parseDouble(text), defaultValue);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private double normalizeConfidence(double value, double defaultValue) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return defaultValue;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value <= 1.0d) {
            return value;
        }
        // Align with system prompt confidence normalization:
        // slight overflow on [0,1] scale -> clamp, percentage-style input -> divide by 100.
        if (value <= 2.0d) {
            return 1.0d;
        }
        if (value <= 100.0d) {
            return value / 100.0d;
        }
        return 1.0d;
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
