package com.memsys.im;

import com.memsys.cli.ConversationCli;
import com.memsys.identity.UserIdentityService;
import com.memsys.llm.LlmClient;
import com.memsys.memory.MemoryAsyncService;
import com.memsys.memory.MemoryManager;
import com.memsys.memory.MemoryReflectionService;
import com.memsys.memory.MemoryScopeContext;
import com.memsys.memory.model.ReflectionResult;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.prompt.AgentGuideService;
import com.memsys.prompt.SystemPromptBuilder;
import com.memsys.rag.RagService;
import com.memsys.skill.SkillService;
import com.memsys.task.ScheduledTaskService;
import com.memsys.task.model.ScheduledTask;
import com.memsys.tool.BaseTool;
import com.memsys.tool.LoadSkillTool;
import com.memsys.tool.SearchRagTool;
import com.memsys.tool.ShellReadTool;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ImChatGatingE2ETest {

    @TempDir
    Path tempDir;

    @Test
    void chatShouldTriggerExampleSearchOnlyForExperiencePurpose() throws Exception {
        TestHarness harness = buildHarness(List.of("experience"), true);

        String body = """
                {
                  "platform": "telegram",
                  "conversationId": "chat_1",
                  "senderId": "user_1",
                  "text": "给我一个类似案例",
                  "temporary": false
                }
                """;

        String content = harness.mockMvc.perform(post("/im/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(content).contains("\"ok\":true");
        assertThat(content).contains("\"reply\":\"assistant reply\"");
        assertThat(harness.exampleSearchCalls.get()).isEqualTo(1);
        verify(harness.scheduledTaskService, never()).listTasks(20);
    }

    @Test
    void chatShouldTriggerTaskLookupOnlyForFollowupPurpose() throws Exception {
        TestHarness harness = buildHarness(List.of("followup"), true);

        String body = """
                {
                  "platform": "telegram",
                  "conversationId": "chat_1",
                  "senderId": "user_1",
                  "text": "继续跟进上个任务",
                  "temporary": false
                }
                """;

        String content = harness.mockMvc.perform(post("/im/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(content).contains("\"ok\":true");
        assertThat(content).contains("\"reply\":\"assistant reply\"");
        assertThat(harness.exampleSearchCalls.get()).isZero();
        verify(harness.scheduledTaskService).listTasks(20);
    }

    private TestHarness buildHarness(List<String> purposes, boolean ragEnabled) {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());

        SkillService skillService = new SkillService(tempDir.toString());
        AtomicInteger exampleSearchCalls = new AtomicInteger();

        RagService ragService = new RagService(storage, tempDir.toString()) {
            @Override
            public List<RelevantMemory> buildSmartContext(String currentMessage, int maxMemories) {
                return List.of();
            }

            @Override
            public List<RelevantMemory> searchExamples(String query, int topK, double minScore) {
                exampleSearchCalls.incrementAndGet();
                return List.of(new RelevantMemory(
                        "example_slot",
                        "problem -> solution",
                        0.9,
                        Map.of("problem", "旧问题", "solution", "旧方案")
                ));
            }
        };

        ScheduledTaskService scheduledTaskService = mock(ScheduledTaskService.class);
        when(scheduledTaskService.drainPendingNotificationsForConversation(anyString(), anyString()))
                .thenReturn(List.of());
        when(scheduledTaskService.listTasks(20)).thenReturn(List.of(matchingTask()));

        ConversationCli conversationCli = new ConversationCli(
                new StubLlmClient(),
                storage,
                new MemoryManager(storage, 100, 30, 15),
                null,
                reflectionServiceWithPurposes(purposes),
                null,
                null,
                new AgentGuideService(tempDir.resolve("missing-Agent.md").toString(), tempDir.toString()),
                new SystemPromptBuilder(),
                new NoopMemoryAsyncService(),
                ragService,
                skillService,
                scheduledTaskService,
                tools(skillService, ragService),
                40,
                15,
                ragEnabled,
                0.35,
                5
        );

        UserIdentityService identityService = new UserIdentityService(storage);
        String unifiedId = identityService.resolveUnifiedId("telegram", "user_1");
        try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(
                MemoryScopeContext.personalScope(unifiedId))) {
            storage.writeMetadata(Map.of(
                    "global_controls", Map.of(
                            "use_saved_memories", true,
                            "use_chat_history", false
                    )
            ));
        }
        @SuppressWarnings("unchecked")
        ObjectProvider telegramProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider feishuProvider = mock(ObjectProvider.class);
        ImRuntimeService runtimeService = new ImRuntimeService(
                conversationCli,
                identityService,
                telegramProvider,
                feishuProvider,
                false
        );
        ImChatController controller = new ImChatController(runtimeService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        return new TestHarness(mockMvc, scheduledTaskService, exampleSearchCalls);
    }

    private MemoryReflectionService reflectionServiceWithPurposes(List<String> purposes) {
        MemoryReflectionService reflectionService = mock(MemoryReflectionService.class);
        when(reflectionService.reflect(anyString(), anyString()))
                .thenReturn(new ReflectionResult(true, "test gating", purposes));
        return reflectionService;
    }

    private List<BaseTool> tools(SkillService skillService, RagService ragService) {
        return List.of(
                new SearchRagTool(ragService, true, 5, 0.35),
                new LoadSkillTool(skillService),
                new ShellReadTool(true, 8, 6000, tempDir.toString())
        );
    }

    private ScheduledTask matchingTask() {
        ScheduledTask task = new ScheduledTask();
        task.setTitle("跟进任务");
        task.setStatus(ScheduledTask.STATUS_PENDING);
        task.setDueAt(LocalDateTime.now().plusHours(1));
        task.setSourcePlatform("telegram");
        task.setSourceConversationId("chat_1");
        return task;
    }

    private record TestHarness(MockMvc mockMvc,
                               ScheduledTaskService scheduledTaskService,
                               AtomicInteger exampleSearchCalls) {
    }

    private static final class StubLlmClient extends LlmClient {
        private StubLlmClient() {
            super("test-key", "http://localhost", "test-model", 2, 1, 0);
        }

        @Override
        public String chatWithTools(String systemPrompt,
                                    List<ChatMessage> messages,
                                    List<ToolDefinition> toolDefinitions,
                                    double temperature) {
            return "assistant reply";
        }

        @Override
        public String chat(String systemPrompt, List<ChatMessage> messages, double temperature) {
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
