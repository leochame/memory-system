package com.memsys.cli;

import com.memsys.llm.LlmClient;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.MemoryAsyncService;
import com.memsys.memory.MemoryManager;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
                null,
                new AgentGuideService(mapFile.toString(), tempDir.toString()),
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

        conversationCli.processUserMessage("first");
        conversationCli.processUserMessage("second");

        assertThat(llmClient.capturedSystemPrompts).hasSize(2);
        assertThat(llmClient.capturedSystemPrompts.get(0)).contains("MAP_LINE: memory first");
        assertThat(llmClient.capturedSystemPrompts.get(1)).doesNotContain("MAP_LINE: memory first");
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

    private static final class RecordingLlmClient extends LlmClient {

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
}
