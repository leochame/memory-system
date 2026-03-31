package com.memsys.cli;

import com.memsys.llm.LlmClient;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.MemoryAsyncService;
import com.memsys.memory.MemoryManager;
import com.memsys.memory.MemoryReflectionService;
import com.memsys.memory.model.MemoryEvidenceTrace;
import com.memsys.memory.model.ReflectionResult;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.prompt.AgentGuideService;
import com.memsys.prompt.SystemPromptBuilder;
import com.memsys.rag.RagService;
import com.memsys.skill.SkillService;
import com.memsys.tool.BaseTool;
import com.memsys.tool.CreateTaskTool;
import com.memsys.tool.LoadSkillTool;
import com.memsys.tool.SearchRagTool;
import com.memsys.tool.ShellReadTool;
import com.memsys.task.ScheduledTaskService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationCliTest {

    @TempDir
    Path tempDir;

    @Test
    void processUserMessageAppendsStartupMapOnlyOnFirstTurn() throws Exception {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        Path mapFile = tempDir.resolve("Agent.md");
        Files.writeString(mapFile, "MAP_LINE: memory first");

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(mapFile.toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new ImmediateMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        conversationCli.processUserMessage("first");
        conversationCli.processUserMessage("second");

        assertThat(llmClient.capturedSystemPrompts).hasSize(2);
        assertThat(llmClient.capturedSystemPrompts.get(0)).contains("MAP_LINE: memory first");
        assertThat(llmClient.capturedSystemPrompts.get(1)).doesNotContain("MAP_LINE: memory first");
    }

    @Test
    void processUserMessageWithMemoryForEvalShouldNotConsumeStartupMap() throws Exception {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        Path mapFile = tempDir.resolve("Agent.md");
        Files.writeString(mapFile, "MAP_LINE: memory first");

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(mapFile.toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new ImmediateMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        conversationCli.processUserMessageWithMemoryForEval("eval");
        conversationCli.processUserMessage("normal");

        assertThat(llmClient.capturedSystemPrompts).hasSize(2);
        assertThat(llmClient.capturedSystemPrompts.get(0)).contains("MAP_LINE: memory first");
        assertThat(llmClient.capturedSystemPrompts.get(1)).contains("MAP_LINE: memory first");
    }

    @Test
    void processUserMessageWithMemoryForEvalShouldNotPersistEvidenceTrace() throws Exception {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new ImmediateMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        String reply = conversationCli.processUserMessageWithMemoryForEval("eval only");
        assertThat(reply).isEqualTo("assistant reply");
        assertThat(conversationCli.getLastEvidenceTrace()).isNotNull();
        assertThat(storage.readMemoryEvidenceTraces(10)).isEmpty();
    }

    @Test
    void processUserMessageExposesLoadSkillToolForNamedSkillOnly() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        skillService.saveSkill("debugging", "Keep answers concise.");
        RecordingLlmClient llmClient = new RecordingLlmClient();

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new ImmediateMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        String reply = conversationCli.processUserMessage("帮我继续排查");

        assertThat(reply).isEqualTo("assistant reply");
        assertThat(llmClient.capturedTools.stream().map(t -> t.specification().name()).toList())
                .contains("load_skill", "run_shell");
        LlmClient.ToolDefinition tool = llmClient.capturedTools.stream()
                .filter(t -> "load_skill".equals(t.specification().name()))
                .findFirst()
                .orElseThrow();

        String loaded = tool.executor().apply(ToolExecutionRequest.builder()
                .id("1")
                .name("load_skill")
                .arguments("{\"name\":\"debugging\"}")
                .build());
        assertThat(loaded)
                .contains("# skill: debugging")
                .contains("Keep answers concise.");

        String missing = tool.executor().apply(ToolExecutionRequest.builder()
                .id("2")
                .name("load_skill")
                .arguments("{\"name\":\"missing\"}")
                .build());
        assertThat(missing)
                .contains("未找到 skill: missing")
                .contains("debugging");

        String invalid = tool.executor().apply(ToolExecutionRequest.builder()
                .id("3")
                .name("load_skill")
                .arguments("{}")
                .build());
        assertThat(invalid).contains("缺少参数 name");
    }

    @Test
    void processUserMessageExposesSearchRagToolWhenEnabled() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        RagService ragService = new RagService(storage, tempDir.toString()) {
            @Override
            public List<RelevantMemory> searchMemories(String query, int topK, double minScore) {
                return List.of(new RelevantMemory(
                        "home_city",
                        "用户住在上海，偏好线下活动",
                        0.92,
                        Map.of("slot_name", "home_city")
                ));
            }
        };

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                ragService,
                skillService,
                null,
                toolsWithRag(skillService, ragService),
                40,
                15,
                true,
                0.35,
                5
        );

        String reply = conversationCli.processUserMessage("我住在哪里");
        assertThat(reply).isEqualTo("assistant reply");

        Optional<LlmClient.ToolDefinition> ragTool = llmClient.capturedTools.stream()
                .filter(tool -> "search_rag".equals(tool.specification().name()))
                .findFirst();
        assertThat(ragTool).isPresent();

        String result = ragTool.orElseThrow().executor().apply(ToolExecutionRequest.builder()
                .id("1")
                .name("search_rag")
                .arguments("{\"query\":\"我住在哪里\"}")
                .build());
        assertThat(result)
                .contains("RAG 检索结果")
                .contains("home_city")
                .contains("92%");
    }

    @Test
    void processUserMessageExposesCreateTaskToolWhenTaskServiceAvailable() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ScheduledTaskService scheduledTaskService = new ScheduledTaskService(
                storage,
                mock(LlmExtractionService.class)
        );

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                scheduledTaskService,
                List.of(
                        new SearchRagTool(null, false, 5, 0.35),
                        new LoadSkillTool(skillService),
                        new CreateTaskTool(scheduledTaskService),
                        new ShellReadTool(true, 8, 6000, tempDir.toString())
                ),
                40,
                15,
                false,
                0.35,
                5
        );

        conversationCli.processUserMessage("明天下午提醒我开会", "telegram", "chat-88", "user-66");

        assertThat(llmClient.capturedTools.stream().map(t -> t.specification().name()).toList())
                .contains("create_task");
    }

    @Test
    void processUserMessageShouldEmitProgressEvents() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        List<ConversationProgressEvent> events = new ArrayList<>();
        conversationCli.processUserMessage("帮我继续排查", "cli", "scope:test", "user:test", events::add);

        assertThat(events).isNotEmpty();
        assertThat(events.get(0).stage()).isEqualTo("accepted");
        assertThat(events.stream().map(ConversationProgressEvent::stage))
                .contains("reflection_started", "evidence_ready", "generating", "completed");
    }

    @Test
    void processUserMessageShouldGenerateEvidenceTraceWithRetrievedAndUsedSplit() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        skillService.saveSkill("debugging", "Keep answers concise.");
        ToolCallingLlmClient llmClient = new ToolCallingLlmClient();

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new ImmediateMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        String reply = conversationCli.processUserMessage("继续看看排查方案");
        assertThat(reply).isEqualTo("assistant reply");

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.retrievedInsights()).isNotEmpty();
        assertThat(trace.loadedSkills()).contains("debugging");
        assertThat(trace.usedSkills()).contains("debugging");

        List<Map<String, Object>> history = storage.readMemoryEvidenceTraces(5);
        assertThat(history).hasSize(1);
        assertThat(history.get(0))
                .containsKeys("retrieved_insights", "used_insights", "loaded_skills", "used_skills", "used_evidence_summary");
    }

    @Test
    void processUserMessageShouldSkipExampleSearchWhenPurposeIsNotExperience() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        MemoryReflectionService reflectionService = reflectionServiceWithPurposes(List.of("personalization"));
        AtomicInteger exampleSearchCalls = new AtomicInteger();

        RagService ragService = new RagService(storage, tempDir.toString()) {
            @Override
            public List<RelevantMemory> buildSmartContext(String currentMessage, int maxMemories) {
                return List.of();
            }

            @Override
            public List<RelevantMemory> searchExamples(String query, int topK, double minScore) {
                exampleSearchCalls.incrementAndGet();
                return List.of();
            }
        };

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                reflectionService,
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                ragService,
                skillService,
                null,
                toolsWithRag(skillService, ragService),
                40,
                15,
                true,
                0.35,
                5
        );

        conversationCli.processUserMessage("继续按我的偏好回答");
        assertThat(exampleSearchCalls.get()).isZero();
    }

    @Test
    void processUserMessageShouldSkipMatchedTaskLookupWhenPurposeIsNotFollowup() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        MemoryReflectionService reflectionService = reflectionServiceWithPurposes(List.of("continuity"));
        ScheduledTaskService scheduledTaskService = mock(ScheduledTaskService.class);
        when(scheduledTaskService.drainPendingNotificationsForConversation(anyString(), anyString())).thenReturn(List.of());

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                reflectionService,
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                scheduledTaskService,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        conversationCli.processUserMessage("继续之前内容", "cli", "scope:test", "user:test");
        verify(scheduledTaskService, never()).listTasks(20);
    }

    @Test
    void processUserMessageShouldTreatUppercaseReflectionPurposesAsValid() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        MemoryReflectionService reflectionService = reflectionServiceWithPurposes(List.of("PERSONALIZATION"));

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                reflectionService,
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        conversationCli.processUserMessage("按我的习惯继续");

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.retrievedInsights()).isNotEmpty();
        assertThat(trace.usedInsights()).isNotEmpty();
    }

    @Test
    void processUserMessageShouldNotInjectInsightsWhenReflectionSaysNoMemory() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                noMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        conversationCli.processUserMessage("你好");

        assertThat(llmClient.capturedSystemPrompts).hasSize(1);
        assertThat(llmClient.capturedSystemPrompts.get(0)).doesNotContain("## 5. 用户画像正文（User Insights）");
    }

    @Test
    void processUserMessageShouldUseDisabledReflectionWhenMemoryControlOff() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", false,
                        "use_chat_history", true
                )
        ));

        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        MemoryReflectionService reflectionService = alwaysNeedMemoryReflectionService();

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                reflectionService,
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        String reply = conversationCli.processUserMessage("继续");
        assertThat(reply).isEqualTo("assistant reply");

        verify(reflectionService, never()).reflect(anyString(), anyString());

        ReflectionResult reflection = conversationCli.getLastReflectionResult();
        assertThat(reflection).isNotNull();
        assertThat(reflection.needs_memory()).isFalse();
        assertThat(reflection.reason()).isEqualTo("记忆开关已关闭，已跳过长期记忆反思与加载。");
        assertThat(reflection.evidence_purposes()).isEmpty();
        assertThat(llmClient.capturedSystemPrompts.get(0)).contains("needs_memory: false");
    }

    @Test
    void processUserMessageShouldFallbackWhenReflectionServiceUnavailable() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                null,
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        String reply = conversationCli.processUserMessage("继续说");
        assertThat(reply).isEqualTo("assistant reply");

        ReflectionResult reflection = conversationCli.getLastReflectionResult();
        assertThat(reflection).isNotNull();
        assertThat(reflection.needs_memory()).isTrue();
        assertThat(reflection.reason()).isEqualTo("反思阶段异常，默认加载长期记忆以保证回答稳定性。");
        assertThat(reflection.evidence_purposes()).containsExactly("continuity");
    }

    @Test
    void processUserMessageShouldFallbackWhenReflectionServiceReturnsNull() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                nullReturningReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        String reply = conversationCli.processUserMessage("继续说");
        assertThat(reply).isEqualTo("assistant reply");

        ReflectionResult reflection = conversationCli.getLastReflectionResult();
        assertThat(reflection).isNotNull();
        assertThat(reflection.needs_memory()).isTrue();
        assertThat(reflection.reason()).isEqualTo("反思阶段异常，默认加载长期记忆以保证回答稳定性。");
        assertThat(llmClient.capturedSystemPrompts.get(0)).contains("needs_memory: true");
    }

    @Test
    void processUserMessageWithMemoryForEvalShouldFallbackWhenReflectionServiceReturnsNull() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();

        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                nullReturningReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        String reply = conversationCli.processUserMessageWithMemoryForEval("eval");
        assertThat(reply).isEqualTo("assistant reply");
        ReflectionResult reflection = conversationCli.getLastReflectionResult();
        assertThat(reflection).isNotNull();
        assertThat(reflection.needs_memory()).isTrue();
        assertThat(reflection.reason()).isEqualTo("反思阶段异常，默认加载长期记忆以保证回答稳定性。");
    }

    @Test
    void getLastEvidenceTraceShouldFallbackToPersistedTraceWhenInMemoryTraceMissing() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        Map<String, Object> reflection = new LinkedHashMap<>();
        reflection.put("needs_memory", true);
        reflection.put("reason", "需要历史偏好");
        reflection.put("evidence_purposes", List.of("personalization"));

        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("timestamp", LocalDateTime.now().toString());
        traceRecord.put("user_message", "根据我的偏好推荐");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", reflection);
        traceRecord.put("retrieved_insights", List.of("food_preference: 不吃鱼"));
        traceRecord.put("used_insights", List.of("food_preference: 不吃鱼"));
        traceRecord.put("retrieved_examples", List.of());
        traceRecord.put("used_examples", List.of());
        traceRecord.put("loaded_skills", List.of("debugging"));
        traceRecord.put("used_skills", List.of());
        traceRecord.put("retrieved_tasks", List.of());
        traceRecord.put("used_tasks", List.of());
        traceRecord.put("used_evidence_summary", "insights 1/1, examples 0/0, skills 0/1, tasks 0/0");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.userMessage()).isEqualTo("根据我的偏好推荐");
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.retrievedInsights()).contains("food_preference: 不吃鱼");
        assertThat(trace.loadedSkills()).contains("debugging");
    }

    @Test
    void getLastEvidenceTraceShouldNormalizeNullLikeFieldsFromPersistedTrace() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        Map<String, Object> reflection = new LinkedHashMap<>();
        reflection.put("needs_memory", true);
        reflection.put("reason", null);
        reflection.put("evidence_purposes", java.util.Arrays.asList("continuity", null, "null"));

        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("timestamp", LocalDateTime.now().toString());
        traceRecord.put("user_message", null);
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", reflection);
        traceRecord.put("retrieved_insights", java.util.Arrays.asList("null", null, "user_insights.md: 偏好简洁"));
        traceRecord.put("used_insights", null);
        traceRecord.put("retrieved_examples", List.of());
        traceRecord.put("used_examples", List.of());
        traceRecord.put("loaded_skills", List.of());
        traceRecord.put("used_skills", List.of());
        traceRecord.put("retrieved_tasks", List.of());
        traceRecord.put("used_tasks", List.of());
        traceRecord.put("used_evidence_summary", null);
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.userMessage()).isEmpty();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().reason()).isEmpty();
        assertThat(trace.reflection().evidence_purposes()).containsExactly("continuity");
        assertThat(trace.retrievedInsights()).containsExactly("user_insights.md: 偏好简洁");
        assertThat(trace.usedEvidenceSummary()).isEmpty();
    }

    @Test
    void getLastEvidenceTraceShouldKeepNeedsMemoryUnknownWhenFieldMissing() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        Map<String, Object> reflection = new LinkedHashMap<>();
        reflection.put("reason", "legacy trace without needs_memory");
        reflection.put("evidence_purposes", List.of("continuity"));

        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("timestamp", LocalDateTime.now().toString());
        traceRecord.put("user_message", "继续上次的话题");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", reflection);
        traceRecord.put("retrieved_insights", List.of("user_insights.md: 喜欢简洁回答"));
        traceRecord.put("used_insights", List.of());
        traceRecord.put("retrieved_examples", List.of());
        traceRecord.put("used_examples", List.of());
        traceRecord.put("loaded_skills", List.of());
        traceRecord.put("used_skills", List.of());
        traceRecord.put("retrieved_tasks", List.of());
        traceRecord.put("used_tasks", List.of());
        traceRecord.put("used_evidence_summary", "insights 0/1, examples 0/0, skills 0/0, tasks 0/0");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNull();
    }

    @Test
    void getLastEvidenceTraceShouldParseLegacyTopLevelReflectionFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("timestamp", LocalDateTime.now().toString());
        traceRecord.put("user_message", "继续上次饮食偏好建议");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("needs_memory", "true");
        traceRecord.put("reason", "legacy top-level reflection");
        traceRecord.put("evidence_purpose", List.of("personalization"));
        traceRecord.put("retrieved_insights", List.of("food_preference: 不吃鱼"));
        traceRecord.put("used_insights", List.of("food_preference: 不吃鱼"));
        traceRecord.put("retrieved_examples", List.of());
        traceRecord.put("used_examples", List.of());
        traceRecord.put("loaded_skills", List.of());
        traceRecord.put("used_skills", List.of());
        traceRecord.put("retrieved_tasks", List.of());
        traceRecord.put("used_tasks", List.of());
        traceRecord.put("used_evidence_summary", "insights 1/1");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().reason()).isEqualTo("legacy top-level reflection");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("personalization");
    }

    @Test
    void getLastEvidenceTraceShouldParseStringifiedReflectionAndListFields() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("timestamp", LocalDateTime.now().toString());
        traceRecord.put("user_message", "继续追踪");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", "{\"needs_memory\":true,\"reason\":\"legacy stringified reflection\",\"evidence_purposes\":[\"continuity\"]}");
        traceRecord.put("retrieved_insights", "[\"food_preference: 不吃鱼\", \"project: memory-system\"]");
        traceRecord.put("used_insights", "[\"project: memory-system\"]");
        traceRecord.put("retrieved_examples", "[]");
        traceRecord.put("used_examples", "[]");
        traceRecord.put("loaded_skills", "[\"debugging\"]");
        traceRecord.put("used_skills", "[\"debugging\"]");
        traceRecord.put("retrieved_tasks", "[]");
        traceRecord.put("used_tasks", "[]");
        traceRecord.put("used_evidence_summary", "insights 1/2");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().reason()).isEqualTo("legacy stringified reflection");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("continuity");
        assertThat(trace.retrievedInsights()).containsExactly("food_preference: 不吃鱼", "project: memory-system");
        assertThat(trace.usedInsights()).containsExactly("project: memory-system");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
    }

    @Test
    void getLastEvidenceTraceShouldNormalizeLegacyConfidenceScales() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        Map<String, Object> olderTrace = new LinkedHashMap<>();
        olderTrace.put("timestamp", LocalDateTime.now().minusMinutes(1).toString());
        olderTrace.put("user_message", "legacy minor overflow");
        olderTrace.put("memory_loaded", true);
        olderTrace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "minor overflow confidence",
                "confidence", 1.2d
        ));
        olderTrace.put("retrieved_insights", List.of());
        olderTrace.put("used_insights", List.of());
        olderTrace.put("retrieved_examples", List.of());
        olderTrace.put("used_examples", List.of());
        olderTrace.put("loaded_skills", List.of());
        olderTrace.put("used_skills", List.of());
        olderTrace.put("retrieved_tasks", List.of());
        olderTrace.put("used_tasks", List.of());
        olderTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(olderTrace);

        Map<String, Object> latestTrace = new LinkedHashMap<>();
        latestTrace.put("timestamp", LocalDateTime.now().toString());
        latestTrace.put("user_message", "legacy percentage confidence");
        latestTrace.put("memory_loaded", true);
        latestTrace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "percentage confidence",
                "confidence", 87
        ));
        latestTrace.put("retrieved_insights", List.of());
        latestTrace.put("used_insights", List.of());
        latestTrace.put("retrieved_examples", List.of());
        latestTrace.put("used_examples", List.of());
        latestTrace.put("loaded_skills", List.of());
        latestTrace.put("used_skills", List.of());
        latestTrace.put("retrieved_tasks", List.of());
        latestTrace.put("used_tasks", List.of());
        latestTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(latestTrace);

        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(2);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).reflection()).isNotNull();
        assertThat(traces.get(1).reflection()).isNotNull();
        assertThat(traces.get(0).reflection().confidence()).isEqualTo(1.0d);
        assertThat(traces.get(1).reflection().confidence()).isEqualTo(0.87d);
    }

    @Test
    void getRecentEvidenceTracesShouldReuseNormalizedParsingForHistoryView() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        Map<String, Object> traceRecord1 = new LinkedHashMap<>();
        traceRecord1.put("timestamp", LocalDateTime.now().minusMinutes(1).toString());
        traceRecord1.put("user_message", "根据我的偏好推荐");
        traceRecord1.put("memory_loaded", true);
        traceRecord1.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "需要历史偏好",
                "evidence_purposes", List.of("personalization")
        ));
        traceRecord1.put("retrieved_insights", List.of("food_preference: 不吃鱼"));
        traceRecord1.put("used_insights", List.of("food_preference: 不吃鱼"));
        traceRecord1.put("retrieved_examples", List.of());
        traceRecord1.put("used_examples", List.of());
        traceRecord1.put("loaded_skills", List.of("debugging"));
        traceRecord1.put("used_skills", List.of());
        traceRecord1.put("retrieved_tasks", List.of());
        traceRecord1.put("used_tasks", List.of());
        traceRecord1.put("used_evidence_summary", "insights 1/1");
        storage.appendMemoryEvidenceTrace(traceRecord1);

        Map<String, Object> traceRecord2 = new LinkedHashMap<>();
        traceRecord2.put("timestamp", LocalDateTime.now().toString());
        traceRecord2.put("user_message", "null");
        traceRecord2.put("memory_loaded", true);
        traceRecord2.put("reflection", Map.of(
                "reason", "legacy trace without needs_memory",
                "evidence_purposes", List.of("continuity")
        ));
        traceRecord2.put("retrieved_insights", java.util.Arrays.asList("null", null, "user_insights.md: 偏好简洁"));
        traceRecord2.put("used_insights", List.of());
        traceRecord2.put("retrieved_examples", List.of());
        traceRecord2.put("used_examples", List.of());
        traceRecord2.put("loaded_skills", List.of());
        traceRecord2.put("used_skills", List.of());
        traceRecord2.put("retrieved_tasks", List.of());
        traceRecord2.put("used_tasks", List.of());
        traceRecord2.put("used_evidence_summary", "null");
        storage.appendMemoryEvidenceTrace(traceRecord2);

        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(2);
        assertThat(traces).hasSize(2);

        MemoryEvidenceTrace latest = traces.get(1);
        assertThat(latest.reflection()).isNull();
        assertThat(latest.userMessage()).isEmpty();
        assertThat(latest.retrievedInsights()).containsExactly("user_insights.md: 偏好简洁");
        assertThat(latest.usedEvidenceSummary()).isEmpty();
    }

    @Test
    void getRecentEvidenceTracesShouldIncludeInMemoryLatestWhenPersistedWriteLagging() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", false
                )
        ));
        SkillService skillService = new SkillService(tempDir.toString());
        RecordingLlmClient llmClient = new RecordingLlmClient();
        ConversationCli conversationCli = new ConversationCli(
                llmClient,
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                alwaysNeedMemoryReflectionService(),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                null,
                skillService,
                null,
                toolsWithoutRag(skillService),
                40,
                15,
                false,
                0.35,
                5
        );

        Map<String, Object> oldTrace = new LinkedHashMap<>();
        oldTrace.put("timestamp", LocalDateTime.now().minusMinutes(10).toString());
        oldTrace.put("user_message", "旧持久化 trace");
        oldTrace.put("memory_loaded", true);
        oldTrace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "需要历史偏好",
                "evidence_purposes", List.of("personalization")
        ));
        oldTrace.put("retrieved_insights", List.of("food_preference: 不吃鱼"));
        oldTrace.put("used_insights", List.of("food_preference: 不吃鱼"));
        oldTrace.put("retrieved_examples", List.of());
        oldTrace.put("used_examples", List.of());
        oldTrace.put("loaded_skills", List.of());
        oldTrace.put("used_skills", List.of());
        oldTrace.put("retrieved_tasks", List.of());
        oldTrace.put("used_tasks", List.of());
        oldTrace.put("used_evidence_summary", "insights 1/1");
        storage.appendMemoryEvidenceTrace(oldTrace);

        conversationCli.processUserMessage("最新一轮只在内存");

        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(2);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).userMessage()).isEqualTo("旧持久化 trace");
        assertThat(traces.get(1).userMessage()).isEqualTo("最新一轮只在内存");
    }

    private List<BaseTool> toolsWithoutRag(SkillService skillService) {
        return List.of(
                new SearchRagTool(null, false, 5, 0.35),
                new LoadSkillTool(skillService),
                new ShellReadTool(true, 8, 6000, tempDir.toString())
        );
    }

    private List<BaseTool> toolsWithRag(SkillService skillService, RagService ragService) {
        return List.of(
                new SearchRagTool(ragService, true, 5, 0.35),
                new LoadSkillTool(skillService),
                new ShellReadTool(true, 8, 6000, tempDir.toString())
        );
    }

    private static class RecordingLlmClient extends LlmClient {

        private List<ToolDefinition> capturedTools = List.of();
        private final List<String> capturedSystemPrompts = new ArrayList<>();

        private RecordingLlmClient() {
            super("test-key", "http://localhost", "test-model", 2, 1, 0);
        }

        @Override
        public String chatWithTools(String systemPrompt,
                                    List<ChatMessage> messages,
                                    List<ToolDefinition> toolDefinitions,
                                    double temperature) {
            this.capturedTools = List.copyOf(toolDefinitions);
            this.capturedSystemPrompts.add(systemPrompt);
            return "assistant reply";
        }

        @Override
        public String chat(String systemPrompt, List<ChatMessage> messages, double temperature) {
            this.capturedSystemPrompts.add(systemPrompt);
            return "assistant reply";
        }
    }

    private static final class ToolCallingLlmClient extends RecordingLlmClient {
        @Override
        public String chatWithTools(String systemPrompt,
                                    List<ChatMessage> messages,
                                    List<ToolDefinition> toolDefinitions,
                                    double temperature) {
            for (ToolDefinition toolDefinition : toolDefinitions) {
                if ("load_skill".equals(toolDefinition.specification().name())) {
                    toolDefinition.executor().apply(ToolExecutionRequest.builder()
                            .id("auto")
                            .name("load_skill")
                            .arguments("{\"name\":\"debugging\"}")
                            .build());
                }
            }
            return super.chatWithTools(systemPrompt, messages, toolDefinitions, temperature);
        }
    }

    private static final class NoopMemoryAsyncService extends MemoryAsyncService {

        private NoopMemoryAsyncService() {
            super(false, 1, 1);
        }

        @Override
        public boolean submit(String taskName, Runnable task) {
            return true;
        }
    }

    private static final class ImmediateMemoryAsyncService extends MemoryAsyncService {

        private ImmediateMemoryAsyncService() {
            super(false, 1, 1);
        }

        @Override
        public boolean submit(String taskName, Runnable task) {
            if (task != null) {
                task.run();
            }
            return true;
        }
    }

    private MemoryReflectionService alwaysNeedMemoryReflectionService() {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString())).thenReturn(ReflectionResult.fallback());
        return reflectionService;
    }

    private MemoryReflectionService reflectionServiceWithPurposes(List<String> purposes) {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString()))
                .thenReturn(new ReflectionResult(
                        true,
                        "CONTINUITY",
                        "需要历史信息",
                        0.8d,
                        "优先检索历史上下文",
                        List.of("RECENT_HISTORY"),
                        purposes
                ));
        return reflectionService;
    }

    private MemoryReflectionService nullReturningReflectionService() {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString())).thenReturn(null);
        return reflectionService;
    }

    private MemoryReflectionService noMemoryReflectionService() {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString()))
                .thenReturn(new ReflectionResult(
                        false,
                        "NOT_NEEDED",
                        "当前问题无需长期记忆。",
                        0.95d,
                        "",
                        List.of(),
                        List.of()
                ));
        return reflectionService;
    }
}
