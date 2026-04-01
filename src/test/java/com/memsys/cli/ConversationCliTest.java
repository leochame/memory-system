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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    void processUserMessageShouldNormalizeMalformedReflectionResultFromService() {
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
                malformedNoMemoryReflectionService(),
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

        ReflectionResult reflection = conversationCli.getLastReflectionResult();
        assertThat(reflection).isNotNull();
        assertThat(reflection.needs_memory()).isFalse();
        assertThat(reflection.memory_purpose()).isEqualTo("NOT_NEEDED");
        assertThat(reflection.reason()).isEqualTo("当前问题可直接回答，无需调用长期记忆。");
        assertThat(reflection.confidence()).isEqualTo(0.87d);
        assertThat(reflection.retrieval_hint()).isEmpty();
        assertThat(reflection.evidence_types()).isEmpty();
        assertThat(reflection.evidence_purposes()).isEmpty();

        String systemPrompt = llmClient.capturedSystemPrompts.get(0);
        assertThat(systemPrompt).contains("needs_memory: false");
        assertThat(systemPrompt).contains("memory_purpose: NOT_NEEDED");
        assertThat(systemPrompt).doesNotContain("evidence_types:");
        assertThat(systemPrompt).doesNotContain("evidence_purposes:");
    }

    @Test
    void processUserMessageShouldNormalizeNullLikeRetrievalHintWhenNeedsMemory() {
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
                nullLikeHintReflectionService(),
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

        ReflectionResult reflection = conversationCli.getLastReflectionResult();
        assertThat(reflection).isNotNull();
        assertThat(reflection.needs_memory()).isTrue();
        assertThat(reflection.retrieval_hint()).isEqualTo("优先检索与用户当前问题最相关的历史证据。");

        String systemPrompt = llmClient.capturedSystemPrompts.get(0);
        assertThat(systemPrompt).contains("retrieval_hint: 优先检索与用户当前问题最相关的历史证据。");
    }

    @Test
    void processUserMessageShouldDeriveEvidenceDefaultsFromMemoryPurpose() {
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
                actionFollowupMalformedEvidenceReflectionService(),
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

        String reply = conversationCli.processUserMessage("继续跟进我上次安排的任务");
        assertThat(reply).isEqualTo("assistant reply");

        ReflectionResult reflection = conversationCli.getLastReflectionResult();
        assertThat(reflection).isNotNull();
        assertThat(reflection.needs_memory()).isTrue();
        assertThat(reflection.memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(reflection.evidence_types()).containsExactly("TASK", "RECENT_HISTORY");
        assertThat(reflection.evidence_purposes()).containsExactly("followup");

        String systemPrompt = llmClient.capturedSystemPrompts.get(0);
        assertThat(systemPrompt).contains("memory_purpose: ACTION_FOLLOWUP");
        assertThat(systemPrompt).contains("evidence_types: TASK, RECENT_HISTORY");
        assertThat(systemPrompt).contains("evidence_purposes: followup");
    }

    @Test
    void processUserMessageShouldNormalizeHyphenatedMemoryPurpose() {
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
                hyphenatedFollowupReflectionService(),
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

        String reply = conversationCli.processUserMessage("继续跟进我上次安排的任务");
        assertThat(reply).isEqualTo("assistant reply");

        ReflectionResult reflection = conversationCli.getLastReflectionResult();
        assertThat(reflection).isNotNull();
        assertThat(reflection.needs_memory()).isTrue();
        assertThat(reflection.memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(reflection.evidence_types()).containsExactly("TASK", "RECENT_HISTORY");
        assertThat(reflection.evidence_purposes()).containsExactly("followup");

        String systemPrompt = llmClient.capturedSystemPrompts.get(0);
        assertThat(systemPrompt).contains("memory_purpose: ACTION_FOLLOWUP");
        assertThat(systemPrompt).contains("evidence_types: TASK, RECENT_HISTORY");
        assertThat(systemPrompt).contains("evidence_purposes: followup");
    }

    @Test
    void processUserMessageShouldNormalizeEvidenceAliasesFromReflectionService() {
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
                evidenceAliasReflectionService(),
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

        ReflectionResult reflection = conversationCli.getLastReflectionResult();
        assertThat(reflection).isNotNull();
        assertThat(reflection.evidence_types()).containsExactly("TASK", "RECENT_HISTORY");
        assertThat(reflection.evidence_purposes()).containsExactly("followup");

        String systemPrompt = llmClient.capturedSystemPrompts.get(0);
        assertThat(systemPrompt).contains("evidence_types: TASK, RECENT_HISTORY");
        assertThat(systemPrompt).contains("evidence_purposes: followup");
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
    void getLastEvidenceTraceShouldParseDoubleStringifiedReflectionAndListFields() {
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
        traceRecord.put("reflection", "\"{\\\"needs_memory\\\":true,\\\"reason\\\":\\\"double stringified reflection\\\",\\\"evidence_purposes\\\":[\\\"continuity\\\"]}\"");
        traceRecord.put("retrieved_insights", "\"[\\\"food_preference: 不吃鱼\\\", \\\"project: memory-system\\\"]\"");
        traceRecord.put("used_insights", "\"[\\\"project: memory-system\\\"]\"");
        traceRecord.put("retrieved_examples", "\"[]\"");
        traceRecord.put("used_examples", "\"[]\"");
        traceRecord.put("loaded_skills", "\"[\\\"debugging\\\"]\"");
        traceRecord.put("used_skills", "\"[\\\"debugging\\\"]\"");
        traceRecord.put("retrieved_tasks", "\"[]\"");
        traceRecord.put("used_tasks", "\"[]\"");
        traceRecord.put("used_evidence_summary", "insights 1/2");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().reason()).isEqualTo("double stringified reflection");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("continuity");
        assertThat(trace.retrievedInsights()).containsExactly("food_preference: 不吃鱼", "project: memory-system");
        assertThat(trace.usedInsights()).containsExactly("project: memory-system");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
    }

    @Test
    void getLastEvidenceTraceShouldParseMultiLayerStringifiedReflectionAndListFields() {
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

        String reflection = "{\"needs_memory\":true,\"reason\":\"multi layer stringified reflection\",\"evidence_purposes\":[\"continuity\"]}";
        String insights = "[\"food_preference: 不吃鱼\", \"project: memory-system\"]";
        String usedInsights = "[\"project: memory-system\"]";
        String skills = "[\"debugging\"]";
        String emptyList = "[]";

        Map<String, Object> traceRecord = new LinkedHashMap<>();
        traceRecord.put("timestamp", LocalDateTime.now().toString());
        traceRecord.put("user_message", "继续追踪");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", quoteJsonString(quoteJsonString(reflection)));
        traceRecord.put("retrieved_insights", quoteJsonString(quoteJsonString(insights)));
        traceRecord.put("used_insights", quoteJsonString(quoteJsonString(usedInsights)));
        traceRecord.put("retrieved_examples", quoteJsonString(quoteJsonString(emptyList)));
        traceRecord.put("used_examples", quoteJsonString(quoteJsonString(emptyList)));
        traceRecord.put("loaded_skills", quoteJsonString(quoteJsonString(skills)));
        traceRecord.put("used_skills", quoteJsonString(quoteJsonString(skills)));
        traceRecord.put("retrieved_tasks", quoteJsonString(quoteJsonString(emptyList)));
        traceRecord.put("used_tasks", quoteJsonString(quoteJsonString(emptyList)));
        traceRecord.put("used_evidence_summary", "insights 1/2");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().reason()).isEqualTo("multi layer stringified reflection");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("continuity");
        assertThat(trace.retrievedInsights()).containsExactly("food_preference: 不吃鱼", "project: memory-system");
        assertThat(trace.usedInsights()).containsExactly("project: memory-system");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
    }

    @Test
    void getLastEvidenceTraceShouldNormalizeReflectionEvidenceFields() {
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
        traceRecord.put("user_message", "normalize reflection fields");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", Map.of(
                "needs_memory", true,
                "memory_purpose", "NOT_NEEDED",
                "reason", "normalize",
                "evidence_types", List.of(" user_insight ", "invalid", "recent_history", "USER_INSIGHT"),
                "evidence_purposes", List.of(" Continuity ", "INVALID", "experience", "continuity")
        ));
        traceRecord.put("retrieved_insights", List.of());
        traceRecord.put("used_insights", List.of());
        traceRecord.put("retrieved_examples", List.of());
        traceRecord.put("used_examples", List.of());
        traceRecord.put("loaded_skills", List.of());
        traceRecord.put("used_skills", List.of());
        traceRecord.put("retrieved_tasks", List.of());
        traceRecord.put("used_tasks", List.of());
        traceRecord.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("CONTINUITY");
        assertThat(trace.reflection().evidence_types()).containsExactly("USER_INSIGHT", "RECENT_HISTORY");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("continuity", "experience");
    }

    @Test
    void getLastEvidenceTraceShouldFallbackEvidenceFieldsWhenNeedsMemoryIsTrue() {
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
        traceRecord.put("user_message", "fallback evidence fields");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", Map.of(
                "needs_memory", true,
                "memory_purpose", "CONTINUITY",
                "reason", "fallback evidence",
                "evidence_types", List.of("invalid_type"),
                "evidence_purpose", List.of("invalid_purpose")
        ));
        traceRecord.put("retrieved_insights", List.of());
        traceRecord.put("used_insights", List.of());
        traceRecord.put("retrieved_examples", List.of());
        traceRecord.put("used_examples", List.of());
        traceRecord.put("loaded_skills", List.of());
        traceRecord.put("used_skills", List.of());
        traceRecord.put("retrieved_tasks", List.of());
        traceRecord.put("used_tasks", List.of());
        traceRecord.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().evidence_types()).containsExactly("SESSION_SUMMARY", "USER_INSIGHT", "RECENT_HISTORY");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("continuity");
    }

    @Test
    void getLastEvidenceTraceShouldDeriveEvidenceFallbackFromMemoryPurpose() {
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
        traceRecord.put("user_message", "followup fallback");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", Map.of(
                "needs_memory", true,
                "memory_purpose", "ACTION_FOLLOWUP",
                "reason", "需要任务跟进",
                "evidence_types", List.of("invalid"),
                "evidence_purposes", List.of("invalid")
        ));
        traceRecord.put("retrieved_insights", List.of());
        traceRecord.put("used_insights", List.of());
        traceRecord.put("retrieved_examples", List.of());
        traceRecord.put("used_examples", List.of());
        traceRecord.put("loaded_skills", List.of());
        traceRecord.put("used_skills", List.of());
        traceRecord.put("retrieved_tasks", List.of());
        traceRecord.put("used_tasks", List.of());
        traceRecord.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_types()).containsExactly("TASK", "RECENT_HISTORY");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
    }

    @Test
    void getLastEvidenceTraceShouldDropEvidenceFieldsWhenNeedsMemoryIsFalse() {
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
        traceRecord.put("user_message", "skip memory");
        traceRecord.put("memory_loaded", false);
        traceRecord.put("reflection", Map.of(
                "needs_memory", false,
                "memory_purpose", "CONTINUITY",
                "reason", "not needed",
                "retrieval_hint", "should_be_dropped",
                "evidence_types", List.of("USER_INSIGHT"),
                "evidence_purposes", List.of("continuity")
        ));
        traceRecord.put("retrieved_insights", List.of());
        traceRecord.put("used_insights", List.of());
        traceRecord.put("retrieved_examples", List.of());
        traceRecord.put("used_examples", List.of());
        traceRecord.put("loaded_skills", List.of());
        traceRecord.put("used_skills", List.of());
        traceRecord.put("retrieved_tasks", List.of());
        traceRecord.put("used_tasks", List.of());
        traceRecord.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("NOT_NEEDED");
        assertThat(trace.reflection().retrieval_hint()).isBlank();
        assertThat(trace.reflection().evidence_types()).isEmpty();
        assertThat(trace.reflection().evidence_purposes()).isEmpty();
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
        latestTrace.put("timestamp", LocalDateTime.now().minusSeconds(10).toString());
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

        Map<String, Object> latestPercentStringTrace = new LinkedHashMap<>();
        latestPercentStringTrace.put("timestamp", LocalDateTime.now().toString());
        latestPercentStringTrace.put("user_message", "legacy percent-string confidence");
        latestPercentStringTrace.put("memory_loaded", true);
        latestPercentStringTrace.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "percent-string confidence",
                "confidence", "85%"
        ));
        latestPercentStringTrace.put("retrieved_insights", List.of());
        latestPercentStringTrace.put("used_insights", List.of());
        latestPercentStringTrace.put("retrieved_examples", List.of());
        latestPercentStringTrace.put("used_examples", List.of());
        latestPercentStringTrace.put("loaded_skills", List.of());
        latestPercentStringTrace.put("used_skills", List.of());
        latestPercentStringTrace.put("retrieved_tasks", List.of());
        latestPercentStringTrace.put("used_tasks", List.of());
        latestPercentStringTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(latestPercentStringTrace);

        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(3);
        assertThat(traces).hasSize(3);
        assertThat(traces.get(0).reflection()).isNotNull();
        assertThat(traces.get(1).reflection()).isNotNull();
        assertThat(traces.get(2).reflection()).isNotNull();
        assertThat(traces.get(0).reflection().confidence()).isEqualTo(1.0d);
        assertThat(traces.get(1).reflection().confidence()).isEqualTo(0.87d);
        assertThat(traces.get(2).reflection().confidence()).isEqualTo(0.85d);
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
    void getRecentEvidenceTracesShouldParseDoubleStringifiedFieldsInHistoryView() {
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
        traceRecord.put("user_message", "history double string");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", "\"{\\\"needs_memory\\\":true,\\\"reason\\\":\\\"history double string\\\",\\\"evidence_purposes\\\":[\\\"continuity\\\"]}\"");
        traceRecord.put("retrieved_insights", "\"[\\\"user_insights.md: 偏好简洁\\\"]\"");
        traceRecord.put("used_insights", "\"[\\\"user_insights.md: 偏好简洁\\\"]\"");
        traceRecord.put("retrieved_examples", "\"[]\"");
        traceRecord.put("used_examples", "\"[]\"");
        traceRecord.put("loaded_skills", "\"[]\"");
        traceRecord.put("used_skills", "\"[]\"");
        traceRecord.put("retrieved_tasks", "\"[]\"");
        traceRecord.put("used_tasks", "\"[]\"");
        traceRecord.put("used_evidence_summary", "insights 1/1");
        storage.appendMemoryEvidenceTrace(traceRecord);

        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(1);
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).reflection()).isNotNull();
        assertThat(traces.get(0).reflection().reason()).isEqualTo("history double string");
        assertThat(traces.get(0).retrievedInsights()).containsExactly("user_insights.md: 偏好简洁");
        assertThat(traces.get(0).usedInsights()).containsExactly("user_insights.md: 偏好简洁");
    }

    @Test
    void getLastEvidenceTraceShouldParseCamelCaseLegacyTraceFields() {
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
        traceRecord.put("userMessage", "camel case trace");
        traceRecord.put("memoryLoaded", true);
        traceRecord.put("reflection", Map.of(
                "needsMemory", true,
                "memoryPurpose", "continuity",
                "reason", "legacy camel reflection",
                "retrievalHint", "优先检索最近上下文",
                "evidenceTypes", List.of("user_insight", "recent_history"),
                "evidencePurposes", List.of("continuity")
        ));
        traceRecord.put("retrievedInsights", List.of("insight:a"));
        traceRecord.put("usedInsights", List.of("insight:a"));
        traceRecord.put("retrievedExamples", List.of("example:a"));
        traceRecord.put("usedExamples", List.of("example:a"));
        traceRecord.put("loadedSkills", List.of("debugging"));
        traceRecord.put("usedSkills", List.of("debugging"));
        traceRecord.put("retrievedTasks", List.of("task:a"));
        traceRecord.put("usedTasks", List.of("task:a"));
        traceRecord.put("usedEvidenceSummary", "all 1/1");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.userMessage()).isEqualTo("camel case trace");
        assertThat(trace.memoryLoaded()).isTrue();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("CONTINUITY");
        assertThat(trace.reflection().reason()).isEqualTo("legacy camel reflection");
        assertThat(trace.reflection().retrieval_hint()).isEqualTo("优先检索最近上下文");
        assertThat(trace.reflection().evidence_types()).containsExactly("USER_INSIGHT", "RECENT_HISTORY");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("continuity");
        assertThat(trace.retrievedInsights()).containsExactly("insight:a");
        assertThat(trace.usedInsights()).containsExactly("insight:a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.usedEvidenceSummary()).isEqualTo("all 1/1");
    }

    @Test
    void getLastEvidenceTraceShouldParseReflectionAliasAndRetrievedSkillsAlias() {
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
        traceRecord.put("user_message", "legacy alias trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection_result", Map.of(
                "needs_memory", true,
                "memory_purpose", "ACTION_FOLLOWUP",
                "reason", "legacy reflection alias",
                "evidence_types", List.of("invalid"),
                "evidence_purposes", List.of("invalid")
        ));
        traceRecord.put("retrieved_insights", List.of());
        traceRecord.put("used_insights", List.of());
        traceRecord.put("retrieved_examples", List.of());
        traceRecord.put("used_examples", List.of());
        traceRecord.put("retrieved_skills", List.of("debugging", "planner"));
        traceRecord.put("used_skills", List.of("planner"));
        traceRecord.put("retrieved_tasks", List.of("task: 跟进周报"));
        traceRecord.put("used_tasks", List.of("task: 跟进周报"));
        traceRecord.put("used_evidence_summary", "skills 1/2");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_types()).containsExactly("TASK", "RECENT_HISTORY");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
    }

    @Test
    void getLastEvidenceTraceShouldParseObjectListEvidenceFields() {
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
        traceRecord.put("user_message", "legacy object list trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", Map.of(
                "needs_memory", true,
                "memory_purpose", "CONTINUITY",
                "reason", "object list fields",
                "evidence_purposes", List.of("continuity")
        ));
        traceRecord.put("retrieved_insights", List.of(
                Map.of("slot_name", "diet_preference"),
                Map.of("content", "偏好简洁表达")
        ));
        traceRecord.put("used_insights", List.of(Map.of("slotName", "diet_preference")));
        traceRecord.put("retrieved_examples", "[{\"title\":\"答辩案例\"}]");
        traceRecord.put("used_examples", List.of(Map.of("name", "答辩案例")));
        traceRecord.put("retrieved_skills", List.of(Map.of("name", "debugging"), Map.of("id", "planner")));
        traceRecord.put("used_skills", List.of(Map.of("value", "debugging")));
        traceRecord.put("retrieved_tasks", List.of(Map.of("title", "提交周报")));
        traceRecord.put("used_tasks", List.of(Map.of("text", "提交周报")));
        traceRecord.put("used_evidence_summary", "object list parsed");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.retrievedInsights()).containsExactly("diet_preference", "偏好简洁表达");
        assertThat(trace.usedInsights()).containsExactly("diet_preference");
        assertThat(trace.retrievedExamples()).containsExactly("答辩案例");
        assertThat(trace.usedExamples()).containsExactly("答辩案例");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("提交周报");
        assertThat(trace.usedTasks()).containsExactly("提交周报");
    }

    @Test
    void getLastEvidenceTraceShouldParseNestedEvidenceGroups() {
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
        traceRecord.put("user_message", "nested evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", Map.of(
                "needs_memory", true,
                "memory_purpose", "ACTION_FOLLOWUP",
                "reason", "nested evidence groups",
                "evidence_purposes", List.of("followup")
        ));
        traceRecord.put("evidence", Map.of(
                "retrieved", Map.of(
                        "insights", List.of("insight-a", "insight-b"),
                        "examples", List.of("example-a"),
                        "skills", List.of("debugging", "planner"),
                        "tasks", List.of("task-a")
                ),
                "used", Map.of(
                        "insights", List.of("insight-a"),
                        "examples", List.of("example-a"),
                        "skills", List.of("debugging"),
                        "tasks", List.of("task-a")
                )
        ));
        traceRecord.put("used_evidence_summary", "nested evidence parsed");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseNestedEvidenceGroupsWithLegacyKeys() {
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
        traceRecord.put("user_message", "nested evidence legacy keys");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", Map.of(
                "needs_memory", true,
                "memory_purpose", "ACTION_FOLLOWUP",
                "reason", "nested evidence legacy keys",
                "evidence_purposes", List.of("followup")
        ));
        traceRecord.put("evidence", Map.of(
                "retrieved", Map.of(
                        "retrieved_insights", List.of("insight-a"),
                        "retrieved_examples", List.of("example-a"),
                        "loaded_skills", List.of("debugging"),
                        "retrieved_tasks", List.of("task-a")
                ),
                "used", Map.of(
                        "used_insights", List.of("insight-a"),
                        "used_examples", List.of("example-a"),
                        "used_skills", List.of("debugging"),
                        "used_tasks", List.of("task-a")
                )
        ));
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedDotPathTraceFields() {
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
        traceRecord.put("user_message", "flattened dot-path evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection.needs_memory", true);
        traceRecord.put("reflection.memory_purpose", "action-followup");
        traceRecord.put("reflection.reason", "flat key trace");
        traceRecord.put("reflection.evidence_purposes", List.of("follow-up"));
        traceRecord.put("evidence.retrieved.insights", List.of("insight-a", "insight-b"));
        traceRecord.put("evidence.used.insights", List.of("insight-a"));
        traceRecord.put("retrieved.examples", List.of("example-a"));
        traceRecord.put("used.examples", List.of("example-a"));
        traceRecord.put("loaded.skills", List.of("debugging", "planner"));
        traceRecord.put("used.skills", List.of("planner"));
        traceRecord.put("evidence.retrieved.tasks", "task-a, task-b");
        traceRecord.put("evidence.used.tasks", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedBracketPathTraceFields() {
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
        traceRecord.put("user_message", "flattened bracket-path evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection[needs_memory]", true);
        traceRecord.put("reflection[memory_purpose]", "action-followup");
        traceRecord.put("reflection[reason]", "flat bracket trace");
        traceRecord.put("reflection[evidence_purposes][0]", "followup");
        traceRecord.put("evidence[retrieved][insights][0]", "insight-a");
        traceRecord.put("evidence[retrieved][insights][1]", "insight-b");
        traceRecord.put("evidence[used][insights][0]", "insight-a");
        traceRecord.put("retrieved[examples][0]", "example-a");
        traceRecord.put("used[examples][0]", "example-a");
        traceRecord.put("loaded[skills][0]", "debugging");
        traceRecord.put("loaded[skills][1]", "planner");
        traceRecord.put("used[skills][0]", "planner");
        traceRecord.put("retrieved[tasks][0]", "task-a");
        traceRecord.put("retrieved[tasks][1]", "task-b");
        traceRecord.put("used[tasks][0]", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedSlashPathTraceFields() {
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
        traceRecord.put("user_message", "flattened slash-path evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection/needs_memory", true);
        traceRecord.put("reflection/memory_purpose", "action-followup");
        traceRecord.put("reflection/reason", "flat slash trace");
        traceRecord.put("reflection/evidence_purposes/0", "followup");
        traceRecord.put("evidence/retrieved/insights/0", "insight-a");
        traceRecord.put("evidence/retrieved/insights/1", "insight-b");
        traceRecord.put("evidence/used/insights/0", "insight-a");
        traceRecord.put("retrieved/examples/0", "example-a");
        traceRecord.put("used/examples/0", "example-a");
        traceRecord.put("loaded/skills/0", "debugging");
        traceRecord.put("loaded/skills/1", "planner");
        traceRecord.put("used/skills/0", "planner");
        traceRecord.put("retrieved/tasks/0", "task-a");
        traceRecord.put("retrieved/tasks/1", "task-b");
        traceRecord.put("used/tasks/0", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedJsonPointerTraceFields() {
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
        traceRecord.put("user_message", "flattened json-pointer evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("/reflection/needs_memory", true);
        traceRecord.put("/reflection/memory_purpose", "action-followup");
        traceRecord.put("/reflection/reason", "flat json pointer trace");
        traceRecord.put("/reflection/evidence_purposes/0", "followup");
        traceRecord.put("/evidence/retrieved/insights/0", "insight-a");
        traceRecord.put("/evidence/retrieved/insights/1", "insight-b");
        traceRecord.put("/evidence/used/insights/0", "insight-a");
        traceRecord.put("/retrieved/examples/0", "example-a");
        traceRecord.put("/used/examples/0", "example-a");
        traceRecord.put("/loaded/skills/0", "debugging");
        traceRecord.put("/loaded/skills/1", "planner");
        traceRecord.put("/used/skills/0", "planner");
        traceRecord.put("/retrieved/tasks/0", "task-a");
        traceRecord.put("/retrieved/tasks/1", "task-b");
        traceRecord.put("/used/tasks/0", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedJsonPointerFragmentTraceFields() {
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
        traceRecord.put("user_message", "flattened json-pointer-fragment evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("#/reflection/needs_memory", true);
        traceRecord.put("#/reflection/memory_purpose", "action-followup");
        traceRecord.put("#/reflection/reason", "flat json pointer fragment trace");
        traceRecord.put("#/reflection/evidence_purposes/0", "followup");
        traceRecord.put("#/evidence/retrieved/insights/0", "insight-a");
        traceRecord.put("#/evidence/retrieved/insights/1", "insight-b");
        traceRecord.put("#/evidence/used/insights/0", "insight-a");
        traceRecord.put("#/retrieved/examples/0", "example-a");
        traceRecord.put("#/used/examples/0", "example-a");
        traceRecord.put("#/loaded/skills/0", "debugging");
        traceRecord.put("#/loaded/skills/1", "planner");
        traceRecord.put("#/used/skills/0", "planner");
        traceRecord.put("#/retrieved/tasks/0", "task-a");
        traceRecord.put("#/retrieved/tasks/1", "task-b");
        traceRecord.put("#/used/tasks/0", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedJsonPathTraceFields() {
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
        traceRecord.put("user_message", "flattened jsonpath evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("$.reflection.needs_memory", true);
        traceRecord.put("$['reflection']['memory_purpose']", "action-followup");
        traceRecord.put("$['reflection'][\"reason\"]", "flat jsonpath trace");
        traceRecord.put("$['reflection']['evidence_purposes'][0]", "followup");
        traceRecord.put("$['evidence']['retrieved']['insights'][0]", "insight-a");
        traceRecord.put("$['evidence']['retrieved']['insights'][1]", "insight-b");
        traceRecord.put("$['evidence']['used']['insights'][0]", "insight-a");
        traceRecord.put("$.retrieved.examples[0]", "example-a");
        traceRecord.put("$.used.examples[0]", "example-a");
        traceRecord.put("$[loaded][skills][0]", "debugging");
        traceRecord.put("$[loaded][skills][1]", "planner");
        traceRecord.put("$[used][skills][0]", "planner");
        traceRecord.put("$[retrieved][tasks][0]", "task-a");
        traceRecord.put("$[retrieved][tasks][1]", "task-b");
        traceRecord.put("$[used][tasks][0]", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedJsonPathFragmentTraceFields() {
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
        traceRecord.put("user_message", "flattened jsonpath-fragment evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("#$.reflection.needs_memory", true);
        traceRecord.put("#$['reflection']['memory_purpose']", "action-followup");
        traceRecord.put("#$['reflection'][\"reason\"]", "flat jsonpath fragment trace");
        traceRecord.put("#$['reflection']['evidence_purposes'][0]", "followup");
        traceRecord.put("#$['evidence']['retrieved']['insights'][0]", "insight-a");
        traceRecord.put("#$['evidence']['retrieved']['insights'][1]", "insight-b");
        traceRecord.put("#$['evidence']['used']['insights'][0]", "insight-a");
        traceRecord.put("#$.retrieved.examples[0]", "example-a");
        traceRecord.put("#$.used.examples[0]", "example-a");
        traceRecord.put("#$[loaded][skills][0]", "debugging");
        traceRecord.put("#$[loaded][skills][1]", "planner");
        traceRecord.put("#$[used][skills][0]", "planner");
        traceRecord.put("#$[retrieved][tasks][0]", "task-a");
        traceRecord.put("#$[retrieved][tasks][1]", "task-b");
        traceRecord.put("#$[used][tasks][0]", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedColonPathTraceFields() {
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
        traceRecord.put("user_message", "flattened colon path evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection:needs_memory", true);
        traceRecord.put("reflection:memory_purpose", "action-followup");
        traceRecord.put("reflection:reason", "flat colon path trace");
        traceRecord.put("reflection:evidence_purposes:0", "followup");
        traceRecord.put("evidence:retrieved:insights:0", "insight-a");
        traceRecord.put("evidence:retrieved:insights:1", "insight-b");
        traceRecord.put("evidence:used:insights:0", "insight-a");
        traceRecord.put("retrieved:examples:0", "example-a");
        traceRecord.put("used:examples:0", "example-a");
        traceRecord.put("loaded:skills:0", "debugging");
        traceRecord.put("loaded:skills:1", "planner");
        traceRecord.put("used:skills:0", "planner");
        traceRecord.put("retrieved:tasks:0", "task-a");
        traceRecord.put("retrieved:tasks:1", "task-b");
        traceRecord.put("used:tasks:0", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedColonPathTraceFieldsWithDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened colon path trace with delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection : needs_memory", true);
        traceRecord.put("reflection : memory_purpose", "action-followup");
        traceRecord.put("reflection : evidence_purposes : 0", "followup");
        traceRecord.put("evidence : retrieved : insights : 0", "insight-a");
        traceRecord.put("evidence : used : insights : 0", "insight-a");
        traceRecord.put("retrieved : examples : 0", "example-a");
        traceRecord.put("used : examples : 0", "example-a");
        traceRecord.put("loaded : skills : 0", "debugging");
        traceRecord.put("used : skills : 0", "debugging");
        traceRecord.put("retrieved : tasks : 0", "task-a");
        traceRecord.put("used : tasks : 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedDoubleUnderscorePathTraceFields() {
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
        traceRecord.put("user_message", "flattened double underscore path evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection__needs_memory", true);
        traceRecord.put("reflection__memory_purpose", "action-followup");
        traceRecord.put("reflection__evidence_purposes__0", "followup");
        traceRecord.put("evidence__retrieved__insights__0", "insight-a");
        traceRecord.put("evidence__retrieved__insights__1", "insight-b");
        traceRecord.put("evidence__used__insights__0", "insight-a");
        traceRecord.put("retrieved__examples__0", "example-a");
        traceRecord.put("used__examples__0", "example-a");
        traceRecord.put("loaded__skills__0", "debugging");
        traceRecord.put("loaded__skills__1", "planner");
        traceRecord.put("used__skills__0", "planner");
        traceRecord.put("retrieved__tasks__0", "task-a");
        traceRecord.put("retrieved__tasks__1", "task-b");
        traceRecord.put("used__tasks__0", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedDoubleUnderscorePathTraceFieldsWithDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened double underscore path trace with delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection _ _ needs_memory", true);
        traceRecord.put("reflection _ _ memory_purpose", "action-followup");
        traceRecord.put("reflection _ _ evidence_purposes _ _ 0", "followup");
        traceRecord.put("evidence _ _ retrieved _ _ insights _ _ 0", "insight-a");
        traceRecord.put("evidence _ _ used _ _ insights _ _ 0", "insight-a");
        traceRecord.put("retrieved _ _ examples _ _ 0", "example-a");
        traceRecord.put("used _ _ examples _ _ 0", "example-a");
        traceRecord.put("loaded _ _ skills _ _ 0", "debugging");
        traceRecord.put("used _ _ skills _ _ 0", "debugging");
        traceRecord.put("retrieved _ _ tasks _ _ 0", "task-a");
        traceRecord.put("used _ _ tasks _ _ 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedPipePathTraceFields() {
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
        traceRecord.put("user_message", "flattened pipe path evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection|needs_memory", true);
        traceRecord.put("reflection|memory_purpose", "action-followup");
        traceRecord.put("reflection|evidence_purposes|0", "followup");
        traceRecord.put("evidence|retrieved|insights|0", "insight-a");
        traceRecord.put("evidence|retrieved|insights|1", "insight-b");
        traceRecord.put("evidence|used|insights|0", "insight-a");
        traceRecord.put("retrieved|examples|0", "example-a");
        traceRecord.put("used|examples|0", "example-a");
        traceRecord.put("loaded|skills|0", "debugging");
        traceRecord.put("loaded|skills|1", "planner");
        traceRecord.put("used|skills|0", "planner");
        traceRecord.put("retrieved|tasks|0", "task-a");
        traceRecord.put("retrieved|tasks|1", "task-b");
        traceRecord.put("used|tasks|0", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedPipePathTraceFieldsWithDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened pipe path trace with delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection | needs_memory", true);
        traceRecord.put("reflection | memory_purpose", "action-followup");
        traceRecord.put("reflection | evidence_purposes | 0", "followup");
        traceRecord.put("evidence | retrieved | insights | 0", "insight-a");
        traceRecord.put("evidence | used | insights | 0", "insight-a");
        traceRecord.put("retrieved | examples | 0", "example-a");
        traceRecord.put("used | examples | 0", "example-a");
        traceRecord.put("loaded | skills | 0", "debugging");
        traceRecord.put("used | skills | 0", "debugging");
        traceRecord.put("retrieved | tasks | 0", "task-a");
        traceRecord.put("used | tasks | 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedPipePathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened pipe path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# | reflection | needs_memory", true);
        traceRecord.put("# | reflection | memory_purpose", "action-followup");
        traceRecord.put("# | reflection | evidence_purposes | 0", "followup");
        traceRecord.put("# | evidence | retrieved | insights | 0", "insight-a");
        traceRecord.put("# | evidence | used | insights | 0", "insight-a");
        traceRecord.put("# | retrieved | examples | 0", "example-a");
        traceRecord.put("# | used | examples | 0", "example-a");
        traceRecord.put("# | loaded | skills | 0", "debugging");
        traceRecord.put("# | used | skills | 0", "debugging");
        traceRecord.put("# | retrieved | tasks | 0", "task-a");
        traceRecord.put("# | used | tasks | 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedBackslashPathTraceFields() {
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
        traceRecord.put("user_message", "flattened backslash path evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection\\needs_memory", true);
        traceRecord.put("reflection\\memory_purpose", "action-followup");
        traceRecord.put("reflection\\evidence_purposes\\0", "followup");
        traceRecord.put("evidence\\retrieved\\insights\\0", "insight-a");
        traceRecord.put("evidence\\retrieved\\insights\\1", "insight-b");
        traceRecord.put("evidence\\used\\insights\\0", "insight-a");
        traceRecord.put("retrieved\\examples\\0", "example-a");
        traceRecord.put("used\\examples\\0", "example-a");
        traceRecord.put("loaded\\skills\\0", "debugging");
        traceRecord.put("loaded\\skills\\1", "planner");
        traceRecord.put("used\\skills\\0", "planner");
        traceRecord.put("retrieved\\tasks\\0", "task-a");
        traceRecord.put("retrieved\\tasks\\1", "task-b");
        traceRecord.put("used\\tasks\\0", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedBackslashPathTraceFieldsWithDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened backslash path trace with delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# \\ reflection \\ needs_memory", true);
        traceRecord.put("# \\ reflection \\ memory_purpose", "action-followup");
        traceRecord.put("# \\ reflection \\ evidence_purposes \\ 0", "followup");
        traceRecord.put("evidence \\ retrieved \\ insights \\ 0", "insight-a");
        traceRecord.put("evidence \\ used \\ insights \\ 0", "insight-a");
        traceRecord.put("retrieved \\ examples \\ 0", "example-a");
        traceRecord.put("used \\ examples \\ 0", "example-a");
        traceRecord.put("loaded \\ skills \\ 0", "debugging");
        traceRecord.put("used \\ skills \\ 0", "debugging");
        traceRecord.put("retrieved \\ tasks \\ 0", "task-a");
        traceRecord.put("used \\ tasks \\ 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedArrowPathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened arrow path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# -> reflection -> needs_memory", true);
        traceRecord.put("# -> reflection -> memory_purpose", "action-followup");
        traceRecord.put("# -> reflection -> evidence_purposes -> 0", "followup");
        traceRecord.put("evidence -> retrieved -> insights -> 0", "insight-a");
        traceRecord.put("evidence -> used -> insights -> 0", "insight-a");
        traceRecord.put("retrieved -> examples -> 0", "example-a");
        traceRecord.put("used -> examples -> 0", "example-a");
        traceRecord.put("loaded -> skills -> 0", "debugging");
        traceRecord.put("used -> skills -> 0", "debugging");
        traceRecord.put("retrieved -> tasks -> 0", "task-a");
        traceRecord.put("used -> tasks -> 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedFatArrowPathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened fat arrow path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# => reflection => needs_memory", true);
        traceRecord.put("# => reflection => memory_purpose", "action-followup");
        traceRecord.put("# => reflection => evidence_purposes => 0", "followup");
        traceRecord.put("evidence => retrieved => insights => 0", "insight-a");
        traceRecord.put("evidence => used => insights => 0", "insight-a");
        traceRecord.put("retrieved => examples => 0", "example-a");
        traceRecord.put("used => examples => 0", "example-a");
        traceRecord.put("loaded => skills => 0", "debugging");
        traceRecord.put("used => skills => 0", "debugging");
        traceRecord.put("retrieved => tasks => 0", "task-a");
        traceRecord.put("used => tasks => 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedDoubleAnglePathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened double angle path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# >> reflection >> needs_memory", true);
        traceRecord.put("# >> reflection >> memory_purpose", "action-followup");
        traceRecord.put("# >> reflection >> evidence_purposes >> 0", "followup");
        traceRecord.put("evidence >> retrieved >> insights >> 0", "insight-a");
        traceRecord.put("evidence >> used >> insights >> 0", "insight-a");
        traceRecord.put("retrieved >> examples >> 0", "example-a");
        traceRecord.put("used >> examples >> 0", "example-a");
        traceRecord.put("loaded >> skills >> 0", "debugging");
        traceRecord.put("used >> skills >> 0", "debugging");
        traceRecord.put("retrieved >> tasks >> 0", "task-a");
        traceRecord.put("used >> tasks >> 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedUnicodeArrowPathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened unicode arrow path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# → reflection → needs_memory", true);
        traceRecord.put("# → reflection → memory_purpose", "action-followup");
        traceRecord.put("# → reflection → evidence_purposes → 0", "followup");
        traceRecord.put("evidence → retrieved → insights → 0", "insight-a");
        traceRecord.put("evidence → used → insights → 0", "insight-a");
        traceRecord.put("retrieved → examples → 0", "example-a");
        traceRecord.put("used → examples → 0", "example-a");
        traceRecord.put("loaded → skills → 0", "debugging");
        traceRecord.put("used → skills → 0", "debugging");
        traceRecord.put("retrieved → tasks → 0", "task-a");
        traceRecord.put("used → tasks → 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedUnicodeFatArrowPathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened unicode fat arrow path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# ⇒ reflection ⇒ needs_memory", true);
        traceRecord.put("# ⇒ reflection ⇒ memory_purpose", "action-followup");
        traceRecord.put("# ⇒ reflection ⇒ evidence_purposes ⇒ 0", "followup");
        traceRecord.put("evidence ⇒ retrieved ⇒ insights ⇒ 0", "insight-a");
        traceRecord.put("evidence ⇒ used ⇒ insights ⇒ 0", "insight-a");
        traceRecord.put("retrieved ⇒ examples ⇒ 0", "example-a");
        traceRecord.put("used ⇒ examples ⇒ 0", "example-a");
        traceRecord.put("loaded ⇒ skills ⇒ 0", "debugging");
        traceRecord.put("used ⇒ skills ⇒ 0", "debugging");
        traceRecord.put("retrieved ⇒ tasks ⇒ 0", "task-a");
        traceRecord.put("used ⇒ tasks ⇒ 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedUnicodeMapstoPathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened unicode mapsto path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# ↦ reflection ↦ needs_memory", true);
        traceRecord.put("# ↦ reflection ↦ memory_purpose", "action-followup");
        traceRecord.put("# ↦ reflection ↦ evidence_purposes ↦ 0", "followup");
        traceRecord.put("evidence ↦ retrieved ↦ insights ↦ 0", "insight-a");
        traceRecord.put("evidence ↦ used ↦ insights ↦ 0", "insight-a");
        traceRecord.put("retrieved ↦ examples ↦ 0", "example-a");
        traceRecord.put("used ↦ examples ↦ 0", "example-a");
        traceRecord.put("loaded ↦ skills ↦ 0", "debugging");
        traceRecord.put("used ↦ skills ↦ 0", "debugging");
        traceRecord.put("retrieved ↦ tasks ↦ 0", "task-a");
        traceRecord.put("used ↦ tasks ↦ 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedUnicodeLongArrowPathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened unicode long arrow path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# ⟶ reflection ⟶ needs_memory", true);
        traceRecord.put("# ⟶ reflection ⟶ memory_purpose", "action-followup");
        traceRecord.put("# ⟶ reflection ⟶ evidence_purposes ⟶ 0", "followup");
        traceRecord.put("evidence ⟶ retrieved ⟶ insights ⟶ 0", "insight-a");
        traceRecord.put("evidence ⟶ used ⟶ insights ⟶ 0", "insight-a");
        traceRecord.put("retrieved ⟶ examples ⟶ 0", "example-a");
        traceRecord.put("used ⟶ examples ⟶ 0", "example-a");
        traceRecord.put("loaded ⟶ skills ⟶ 0", "debugging");
        traceRecord.put("used ⟶ skills ⟶ 0", "debugging");
        traceRecord.put("retrieved ⟶ tasks ⟶ 0", "task-a");
        traceRecord.put("used ⟶ tasks ⟶ 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedSemicolonPathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened semicolon path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# ; reflection ; needs_memory", true);
        traceRecord.put("# ; reflection ; memory_purpose", "action-followup");
        traceRecord.put("# ; reflection ; evidence_purposes ; 0", "followup");
        traceRecord.put("evidence ; retrieved ; insights ; 0", "insight-a");
        traceRecord.put("evidence ; used ; insights ; 0", "insight-a");
        traceRecord.put("retrieved ; examples ; 0", "example-a");
        traceRecord.put("used ; examples ; 0", "example-a");
        traceRecord.put("loaded ; skills ; 0", "debugging");
        traceRecord.put("used ; skills ; 0", "debugging");
        traceRecord.put("retrieved ; tasks ; 0", "task-a");
        traceRecord.put("used ; tasks ; 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedCommaPathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened comma path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# , reflection , needs_memory", true);
        traceRecord.put("# , reflection , memory_purpose", "action-followup");
        traceRecord.put("# , reflection , evidence_purposes , 0", "followup");
        traceRecord.put("evidence , retrieved , insights , 0", "insight-a");
        traceRecord.put("evidence , used , insights , 0", "insight-a");
        traceRecord.put("retrieved , examples , 0", "example-a");
        traceRecord.put("used , examples , 0", "example-a");
        traceRecord.put("loaded , skills , 0", "debugging");
        traceRecord.put("used , skills , 0", "debugging");
        traceRecord.put("retrieved , tasks , 0", "task-a");
        traceRecord.put("used , tasks , 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedFullWidthDelimiterPathTraceFieldsWithFragmentDelimiterWhitespace() {
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
        traceRecord.put("user_message", "flattened full-width delimiter path trace with fragment delimiter whitespace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# ， reflection ， needs_memory", true);
        traceRecord.put("# ， reflection ， memory_purpose", "action-followup");
        traceRecord.put("# ， reflection ， evidence_purposes ， 0", "followup");
        traceRecord.put("evidence ： retrieved ： insights ： 0", "insight-a");
        traceRecord.put("evidence ： used ： insights ： 0", "insight-a");
        traceRecord.put("retrieved ； examples ； 0", "example-a");
        traceRecord.put("used ； examples ； 0", "example-a");
        traceRecord.put("loaded ｜ skills ｜ 0", "debugging");
        traceRecord.put("used ｜ skills ｜ 0", "debugging");
        traceRecord.put("retrieved ， tasks ， 0", "task-a");
        traceRecord.put("used ， tasks ， 0", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedFullWidthDelimiterPathTraceFieldsWithRepeatedFragmentDelimiters() {
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
        traceRecord.put("memory_loaded", true);
        traceRecord.put("# ， ， reflection ， needs_memory", true);
        traceRecord.put("# ， ， reflection ， evidence_purposes ， 0", "followup");
        traceRecord.put("# ， ， evidence ： retrieved ： insights ： 0", "insight-a");
        traceRecord.put("# ， ， evidence ： used ： insights ： 0", "insight-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedPathRootWithNamingDrift() {
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
        traceRecord.put("user_message", "flattened path root naming drift evidence trace");
        traceRecord.put("MEMORY-LOADED", "YES");
        traceRecord.put("#/REFLECTION-RESULT/NEEDS-MEMORY", "Y");
        traceRecord.put("#/REFLECTION-RESULT/MEMORY-PURPOSE", "action-followup");
        traceRecord.put("#/REFLECTION-RESULT/REASON", "flat path root naming drift trace");
        traceRecord.put("#/REFLECTION-RESULT/EVIDENCE-PURPOSE/0", "follow-up");
        traceRecord.put("#/RETRIEVED-INSIGHTS/0", "insight-a");
        traceRecord.put("#/RETRIEVED-INSIGHTS/1", "insight-b");
        traceRecord.put("#/USED-INSIGHTS/0", "insight-a");
        traceRecord.put("#/RETRIEVED-EXAMPLES/0", "example-a");
        traceRecord.put("#/USED-EXAMPLES/0", "example-a");
        traceRecord.put("#/RETRIEVED-SKILLS/0", "debugging");
        traceRecord.put("#/RETRIEVED-SKILLS/1", "planner");
        traceRecord.put("#/USED-SKILLS/0", "planner");
        traceRecord.put("#/RETRIEVED-TASKS/0", "task-a");
        traceRecord.put("#/RETRIEVED-TASKS/1", "task-b");
        traceRecord.put("#/USED-TASKS/0", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.memoryLoaded()).isTrue();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseFlattenedPathRootWithNamingDriftAndWhitespace() {
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
        traceRecord.put("user_message", "flattened path root naming drift with whitespace");
        traceRecord.put("MEMORY-LOADED", "YES");
        traceRecord.put("  #/REFLECTION-RESULT/NEEDS-MEMORY  ", "Y");
        traceRecord.put("  #/RETRIEVED-INSIGHTS/0  ", "insight-a");
        traceRecord.put("  #/USED-INSIGHTS/0  ", "insight-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.retrievedInsights()).containsExactly("insight-a");
        assertThat(trace.usedInsights()).containsExactly("insight-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseKebabAndUppercaseTraceFields() {
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
        traceRecord.put("TIME_STAMP", LocalDateTime.now().toString());
        traceRecord.put("USER-MESSAGE", "kebab uppercase trace");
        traceRecord.put("MEMORY-LOADED", "YES");
        traceRecord.put("REFLECTION-RESULT", Map.of(
                "NEEDS-MEMORY", "Y",
                "MEMORY-PURPOSE", "action-followup",
                "REASON", "kebab uppercase reflection",
                "EVIDENCE-PURPOSE", "follow-up"
        ));
        traceRecord.put("RETRIEVED-INSIGHTS", "insight-a | insight-b");
        traceRecord.put("USED-INSIGHTS", "insight-b");
        traceRecord.put("RETRIEVED-EXAMPLES", "example-a");
        traceRecord.put("USED-EXAMPLES", "example-a");
        traceRecord.put("LOADED-SKILLS", "debugging, planner");
        traceRecord.put("USED-SKILLS", "planner");
        traceRecord.put("RETRIEVED-TASKS", "task-a");
        traceRecord.put("USED-TASKS", "task-a");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.memoryLoaded()).isTrue();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("ACTION_FOLLOWUP");
        assertThat(trace.reflection().evidence_purposes()).containsExactly("followup");
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-b");
        assertThat(trace.retrievedExamples()).containsExactly("example-a");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a");
        assertThat(trace.usedTasks()).containsExactly("task-a");
    }

    @Test
    void getLastEvidenceTraceShouldParseMapStyleEvidenceFields() {
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
        traceRecord.put("user_message", "map style evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", Map.of(
                "needs_memory", true,
                "memory_purpose", "ACTION_FOLLOWUP",
                "reason", "map field trace",
                "evidence_purposes", List.of("followup")
        ));
        traceRecord.put("retrieved_insights", Map.of("insight-a", true, "insight-b", "yes", "insight-c", false));
        traceRecord.put("used_insights", Map.of("insight-b", true));
        traceRecord.put("retrieved_examples", Map.of(
                "case-1", Map.of("title", "example-a"),
                "case-2", Map.of("content", "example-b")
        ));
        traceRecord.put("used_examples", Map.of("case-2", Map.of("content", "example-b")));
        traceRecord.put("loaded_skills", Map.of("debugging", true, "planner", "yes", "refactor", "no"));
        traceRecord.put("used_skills", Map.of("planner", true));
        traceRecord.put("retrieved_tasks", Map.of("task-a", 1, "task-b", "on", "task-c", 0));
        traceRecord.put("used_tasks", Map.of("task-b", true));
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.retrievedInsights()).containsExactlyInAnyOrder("insight-a", "insight-b");
        assertThat(trace.usedInsights()).containsExactly("insight-b");
        assertThat(trace.retrievedExamples()).containsExactlyInAnyOrder("example-a", "example-b");
        assertThat(trace.usedExamples()).containsExactly("example-b");
        assertThat(trace.loadedSkills()).containsExactlyInAnyOrder("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactlyInAnyOrder("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldParseDelimitedStringEvidenceFields() {
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
        traceRecord.put("user_message", "delimited evidence trace");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", Map.of(
                "needs_memory", true,
                "memory_purpose", "ACTION_FOLLOWUP",
                "reason", "分隔字符串兼容",
                "evidence_purposes", List.of("followup")
        ));
        traceRecord.put("retrieved_insights", "insight-a, insight-b; insight-c");
        traceRecord.put("used_insights", "insight-a | insight-c");
        traceRecord.put("retrieved_examples", "- example-a\n- example-b");
        traceRecord.put("used_examples", "example-a");
        traceRecord.put("retrieved_skills", "debugging | planner");
        traceRecord.put("used_skills", "planner");
        traceRecord.put("retrieved_tasks", "1. task-a\n2. task-b");
        traceRecord.put("used_tasks", "task-b");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.retrievedInsights()).containsExactly("insight-a", "insight-b", "insight-c");
        assertThat(trace.usedInsights()).containsExactly("insight-a", "insight-c");
        assertThat(trace.retrievedExamples()).containsExactly("example-a", "example-b");
        assertThat(trace.usedExamples()).containsExactly("example-a");
        assertThat(trace.loadedSkills()).containsExactly("debugging", "planner");
        assertThat(trace.usedSkills()).containsExactly("planner");
        assertThat(trace.retrievedTasks()).containsExactly("task-a", "task-b");
        assertThat(trace.usedTasks()).containsExactly("task-b");
    }

    @Test
    void getLastEvidenceTraceShouldIgnoreNullLikeEvidenceTokens() {
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
        traceRecord.put("user_message", "继续优化");
        traceRecord.put("memory_loaded", true);
        traceRecord.put("reflection", Map.of(
                "needs_memory", true,
                "reason", "过滤噪声证据",
                "evidence_purposes", List.of("followup")
        ));
        traceRecord.put("retrieved_insights", List.of("null", "undefined", "user_insights.md: 喜欢结构化输出"));
        traceRecord.put("used_insights", List.of("n/a", "user_insights.md: 喜欢结构化输出"));
        traceRecord.put("retrieved_examples", "\"[\\\"none\\\",\\\"答辩案例\\\"]\"");
        traceRecord.put("used_examples", List.of("N/A", "答辩案例"));
        traceRecord.put("loaded_skills", List.of("none", "debugging"));
        traceRecord.put("used_skills", List.of("undefined", "debugging"));
        traceRecord.put("retrieved_tasks", List.of("none", "提交周报"));
        traceRecord.put("used_tasks", List.of("null", "提交周报"));
        traceRecord.put("used_evidence_summary", "insights 1/1, examples 1/1, skills 1/1, tasks 1/1");
        storage.appendMemoryEvidenceTrace(traceRecord);

        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        assertThat(trace).isNotNull();
        assertThat(trace.retrievedInsights()).containsExactly("user_insights.md: 喜欢结构化输出");
        assertThat(trace.usedInsights()).containsExactly("user_insights.md: 喜欢结构化输出");
        assertThat(trace.retrievedExamples()).containsExactly("答辩案例");
        assertThat(trace.usedExamples()).containsExactly("答辩案例");
        assertThat(trace.loadedSkills()).containsExactly("debugging");
        assertThat(trace.usedSkills()).containsExactly("debugging");
        assertThat(trace.retrievedTasks()).containsExactly("提交周报");
        assertThat(trace.usedTasks()).containsExactly("提交周报");
    }

    @Test
    void getLastEvidenceTraceShouldParseYesNoBooleanLegacyFields() {
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
        traceRecord.put("user_message", "legacy yes/no");
        traceRecord.put("memory_loaded", "yes");
        traceRecord.put("reflection", Map.of(
                "needs_memory", "y",
                "memory_purpose", "continuity",
                "reason", "legacy yes/no bool",
                "evidence_purposes", List.of("continuity")
        ));
        traceRecord.put("retrieved_insights", List.of("insight:a"));
        traceRecord.put("used_insights", List.of("insight:a"));
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
        assertThat(trace.memoryLoaded()).isTrue();
        assertThat(trace.reflection()).isNotNull();
        assertThat(trace.reflection().needs_memory()).isTrue();
        assertThat(trace.reflection().memory_purpose()).isEqualTo("CONTINUITY");
    }

    @Test
    void getRecentEvidenceTracesShouldParseLegacyTimestampFormats() {
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

        Map<String, Object> offsetTrace = new LinkedHashMap<>();
        offsetTrace.put("timestamp", "2026-03-31T18:30:00+08:00");
        offsetTrace.put("user_message", "offset ts");
        offsetTrace.put("memory_loaded", true);
        offsetTrace.put("reflection", Map.of("needs_memory", true, "reason", "offset"));
        offsetTrace.put("retrieved_insights", List.of());
        offsetTrace.put("used_insights", List.of());
        offsetTrace.put("retrieved_examples", List.of());
        offsetTrace.put("used_examples", List.of());
        offsetTrace.put("loaded_skills", List.of());
        offsetTrace.put("used_skills", List.of());
        offsetTrace.put("retrieved_tasks", List.of());
        offsetTrace.put("used_tasks", List.of());
        offsetTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(offsetTrace);

        Map<String, Object> legacyTrace = new LinkedHashMap<>();
        legacyTrace.put("timestamp", "2026-03-31 18:31:05");
        legacyTrace.put("user_message", "legacy ts");
        legacyTrace.put("memory_loaded", true);
        legacyTrace.put("reflection", Map.of("needs_memory", true, "reason", "legacy"));
        legacyTrace.put("retrieved_insights", List.of());
        legacyTrace.put("used_insights", List.of());
        legacyTrace.put("retrieved_examples", List.of());
        legacyTrace.put("used_examples", List.of());
        legacyTrace.put("loaded_skills", List.of());
        legacyTrace.put("used_skills", List.of());
        legacyTrace.put("retrieved_tasks", List.of());
        legacyTrace.put("used_tasks", List.of());
        legacyTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(legacyTrace);

        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(2);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).timestamp()).isEqualTo(LocalDateTime.of(2026, 3, 31, 18, 30, 0));
        assertThat(traces.get(1).timestamp()).isEqualTo(LocalDateTime.of(2026, 3, 31, 18, 31, 5));
    }

    @Test
    void getRecentEvidenceTracesShouldNormalizeOffsetTimestampsByInstant() {
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

        Map<String, Object> utcTrace = new LinkedHashMap<>();
        utcTrace.put("timestamp", "2026-03-31T10:30:00Z");
        utcTrace.put("user_message", "utc ts");
        utcTrace.put("memory_loaded", true);
        utcTrace.put("reflection", Map.of("needs_memory", true, "reason", "utc"));
        utcTrace.put("retrieved_insights", List.of());
        utcTrace.put("used_insights", List.of());
        utcTrace.put("retrieved_examples", List.of());
        utcTrace.put("used_examples", List.of());
        utcTrace.put("loaded_skills", List.of());
        utcTrace.put("used_skills", List.of());
        utcTrace.put("retrieved_tasks", List.of());
        utcTrace.put("used_tasks", List.of());
        utcTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(utcTrace);

        Map<String, Object> cstTrace = new LinkedHashMap<>();
        cstTrace.put("timestamp", "2026-03-31T18:30:00+08:00");
        cstTrace.put("user_message", "cst ts");
        cstTrace.put("memory_loaded", true);
        cstTrace.put("reflection", Map.of("needs_memory", true, "reason", "cst"));
        cstTrace.put("retrieved_insights", List.of());
        cstTrace.put("used_insights", List.of());
        cstTrace.put("retrieved_examples", List.of());
        cstTrace.put("used_examples", List.of());
        cstTrace.put("loaded_skills", List.of());
        cstTrace.put("used_skills", List.of());
        cstTrace.put("retrieved_tasks", List.of());
        cstTrace.put("used_tasks", List.of());
        cstTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(cstTrace);

        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(2);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).timestamp()).isEqualTo(traces.get(1).timestamp());
    }

    @Test
    void getRecentEvidenceTracesShouldParseEpochTimestampFormats() {
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

        long epochSeconds = 1_711_881_000L;
        long epochMillis = 1_711_881_005_123L;

        Map<String, Object> epochSecondTrace = new LinkedHashMap<>();
        epochSecondTrace.put("timestamp", epochSeconds);
        epochSecondTrace.put("user_message", "epoch seconds");
        epochSecondTrace.put("memory_loaded", true);
        epochSecondTrace.put("reflection", Map.of("needs_memory", true, "reason", "epoch-seconds"));
        epochSecondTrace.put("retrieved_insights", List.of());
        epochSecondTrace.put("used_insights", List.of());
        epochSecondTrace.put("retrieved_examples", List.of());
        epochSecondTrace.put("used_examples", List.of());
        epochSecondTrace.put("loaded_skills", List.of());
        epochSecondTrace.put("used_skills", List.of());
        epochSecondTrace.put("retrieved_tasks", List.of());
        epochSecondTrace.put("used_tasks", List.of());
        epochSecondTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(epochSecondTrace);

        Map<String, Object> epochMillisTrace = new LinkedHashMap<>();
        epochMillisTrace.put("timestamp", String.valueOf(epochMillis));
        epochMillisTrace.put("user_message", "epoch millis");
        epochMillisTrace.put("memory_loaded", true);
        epochMillisTrace.put("reflection", Map.of("needs_memory", true, "reason", "epoch-millis"));
        epochMillisTrace.put("retrieved_insights", List.of());
        epochMillisTrace.put("used_insights", List.of());
        epochMillisTrace.put("retrieved_examples", List.of());
        epochMillisTrace.put("used_examples", List.of());
        epochMillisTrace.put("loaded_skills", List.of());
        epochMillisTrace.put("used_skills", List.of());
        epochMillisTrace.put("retrieved_tasks", List.of());
        epochMillisTrace.put("used_tasks", List.of());
        epochMillisTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(epochMillisTrace);

        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(2);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).timestamp()).isEqualTo(
                Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDateTime());
        assertThat(traces.get(1).timestamp()).isEqualTo(
                Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime());
    }

    @Test
    void getRecentEvidenceTracesShouldParseEpochMicroAndNanoTimestampFormats() {
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

        long epochMicros = 1_711_881_005_123_000L;
        long epochNanos = 1_711_881_010_456_000_000L;

        Map<String, Object> epochMicrosTrace = new LinkedHashMap<>();
        epochMicrosTrace.put("timestamp", epochMicros);
        epochMicrosTrace.put("user_message", "epoch micros");
        epochMicrosTrace.put("memory_loaded", true);
        epochMicrosTrace.put("reflection", Map.of("needs_memory", true, "reason", "epoch-micros"));
        epochMicrosTrace.put("retrieved_insights", List.of());
        epochMicrosTrace.put("used_insights", List.of());
        epochMicrosTrace.put("retrieved_examples", List.of());
        epochMicrosTrace.put("used_examples", List.of());
        epochMicrosTrace.put("loaded_skills", List.of());
        epochMicrosTrace.put("used_skills", List.of());
        epochMicrosTrace.put("retrieved_tasks", List.of());
        epochMicrosTrace.put("used_tasks", List.of());
        epochMicrosTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(epochMicrosTrace);

        Map<String, Object> epochNanosTrace = new LinkedHashMap<>();
        epochNanosTrace.put("timestamp", String.valueOf(epochNanos));
        epochNanosTrace.put("user_message", "epoch nanos");
        epochNanosTrace.put("memory_loaded", true);
        epochNanosTrace.put("reflection", Map.of("needs_memory", true, "reason", "epoch-nanos"));
        epochNanosTrace.put("retrieved_insights", List.of());
        epochNanosTrace.put("used_insights", List.of());
        epochNanosTrace.put("retrieved_examples", List.of());
        epochNanosTrace.put("used_examples", List.of());
        epochNanosTrace.put("loaded_skills", List.of());
        epochNanosTrace.put("used_skills", List.of());
        epochNanosTrace.put("retrieved_tasks", List.of());
        epochNanosTrace.put("used_tasks", List.of());
        epochNanosTrace.put("used_evidence_summary", "none");
        storage.appendMemoryEvidenceTrace(epochNanosTrace);

        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(2);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).timestamp()).isEqualTo(
                Instant.ofEpochMilli(epochMicros / 1_000L).atZone(ZoneId.systemDefault()).toLocalDateTime());
        assertThat(traces.get(1).timestamp()).isEqualTo(
                Instant.ofEpochMilli(epochNanos / 1_000_000L).atZone(ZoneId.systemDefault()).toLocalDateTime());
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

    private String quoteJsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
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

    private MemoryReflectionService malformedNoMemoryReflectionService() {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString()))
                .thenReturn(new ReflectionResult(
                        false,
                        "CONTINUITY",
                        "null",
                        87.0d,
                        "legacy hint should be dropped",
                        List.of("TASK"),
                        List.of("followup")
                ));
        return reflectionService;
    }

    private MemoryReflectionService nullLikeHintReflectionService() {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString()))
                .thenReturn(new ReflectionResult(
                        true,
                        "CONTINUITY",
                        "需要历史上下文",
                        0.91d,
                        "N/A",
                        List.of("RECENT_HISTORY"),
                        List.of("continuity")
                ));
        return reflectionService;
    }

    private MemoryReflectionService actionFollowupMalformedEvidenceReflectionService() {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString()))
                .thenReturn(new ReflectionResult(
                        true,
                        "ACTION_FOLLOWUP",
                        "需要跟进任务",
                        0.89d,
                        "优先看最近任务",
                        List.of("INVALID"),
                        List.of("INVALID")
                ));
        return reflectionService;
    }

    private MemoryReflectionService hyphenatedFollowupReflectionService() {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString()))
                .thenReturn(new ReflectionResult(
                        true,
                        "action-followup",
                        "需要跟进任务",
                        0.89d,
                        "优先看最近任务",
                        List.of("INVALID"),
                        List.of("INVALID")
                ));
        return reflectionService;
    }

    private MemoryReflectionService evidenceAliasReflectionService() {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString()))
                .thenReturn(new ReflectionResult(
                        true,
                        "ACTION_FOLLOWUP",
                        "需要跟进任务",
                        0.89d,
                        "优先看最近任务",
                        List.of("task", "recentHistory"),
                        List.of("follow-up", "n/a")
                ));
        return reflectionService;
    }
}
