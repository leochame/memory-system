package com.memsys.tool;

import com.memsys.llm.LlmClient;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.rag.RagService;
import com.memsys.skill.SkillService;
import com.memsys.task.ScheduledTaskService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ToolCompositionTest {

    @TempDir
    Path tempDir;

    @Test
    void buildConversationToolsIncludesSearchRagWhenEnabled() {
        SkillService skillService = new SkillService(tempDir.toString());
        RagService ragService = new RagService(new MemoryStorage(tempDir.toString()), tempDir.toString()) {
            @Override
            public List<RelevantMemory> searchMemories(String query, int topK, double minScore) {
                return List.of(new RelevantMemory(
                        "home_city",
                        "用户住在上海，偏好线下活动",
                        0.91,
                        Map.of("slot_name", "home_city")
                ));
            }
        };

        List<BaseTool> toolsForConversation = List.of(
                new SearchRagTool(ragService, true, 3, 0.3),
                new LoadSkillTool(skillService),
                new ShellReadTool(true, 8, 6000, tempDir.toString())
        );
        List<LlmClient.ToolDefinition> tools = buildConversationTools(toolsForConversation, List.of());

        Optional<LlmClient.ToolDefinition> ragTool = tools.stream()
                .filter(t -> "search_rag".equals(t.specification().name()))
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
                .contains("91%");
    }

    @Test
    void buildConversationToolsIncludesBothSearchRagAndLoadSkill() {
        SkillService skillService = new SkillService(tempDir.toString());
        skillService.saveSkill("debugging", "先收敛复现路径，再缩小变更范围。");

        RagService ragService = new RagService(new MemoryStorage(tempDir.toString()), tempDir.toString()) {
            @Override
            public List<RelevantMemory> searchMemories(String query, int topK, double minScore) {
                return List.of(new RelevantMemory(
                        "project_context",
                        "用户正在重构 CLI UI 与工具链路",
                        0.88,
                        Map.of("slot_name", "project_context")
                ));
            }
        };

        List<BaseTool> toolsForConversation = List.of(
                new SearchRagTool(ragService, true, 3, 0.3),
                new LoadSkillTool(skillService),
                new ShellReadTool(true, 8, 6000, tempDir.toString()),
                new ShellCommandTool(true, "/bin/sh", 10, 6000, tempDir.toString())
        );
        List<LlmClient.ToolDefinition> tools = buildConversationTools(toolsForConversation, List.of("debugging"));

        assertThat(tools).hasSize(4);
        assertThat(tools.stream().map(t -> t.specification().name()).toList())
                .contains("search_rag", "load_skill", "run_shell", "run_shell_command");

        LlmClient.ToolDefinition skillTool = tools.stream()
                .filter(t -> "load_skill".equals(t.specification().name()))
                .findFirst()
                .orElseThrow();
        String loaded = skillTool.executor().apply(ToolExecutionRequest.builder()
                .id("2")
                .name("load_skill")
                .arguments("{\"name\":\"debugging\"}")
                .build());
        assertThat(loaded).contains("debugging").contains("复现路径");
    }

    @Test
    void runShellToolCanReadFilesInWorkspace() throws Exception {
        Files.writeString(tempDir.resolve("sample.txt"), "hello shell");
        List<BaseTool> toolsForConversation = List.of(
                new SearchRagTool(null, false, 5, 0.35),
                new LoadSkillTool(new SkillService(tempDir.toString())),
                new ShellReadTool(true, 8, 6000, tempDir.toString())
        );
        List<LlmClient.ToolDefinition> tools = buildConversationTools(toolsForConversation, List.of());
        LlmClient.ToolDefinition shellTool = tools.stream()
                .filter(t -> "run_shell".equals(t.specification().name()))
                .findFirst()
                .orElseThrow();

        String result = shellTool.executor().apply(ToolExecutionRequest.builder()
                .id("3")
                .name("run_shell")
                .arguments("{\"command\":\"cat sample.txt\",\"cwd\":\".\"}")
                .build());

        assertThat(result).contains("exit_code=0");
        assertThat(result).contains("hello shell");
    }

    @Test
    void buildConversationToolsIncludesCreateTaskWhenAvailable() {
        MemoryStorage storage = new MemoryStorage(tempDir.toString());
        ScheduledTaskService scheduledTaskService = new ScheduledTaskService(
                storage,
                mock(LlmExtractionService.class)
        );

        List<BaseTool> toolsForConversation = List.of(
                new SearchRagTool(null, false, 5, 0.35),
                new CreateTaskTool(scheduledTaskService),
                new ShellReadTool(true, 8, 6000, tempDir.toString()),
                new ShellCommandTool(true, "/bin/sh", 10, 6000, tempDir.toString())
        );

        List<LlmClient.ToolDefinition> tools = buildConversationTools(toolsForConversation, List.of());
        assertThat(tools.stream().map(t -> t.specification().name()).toList())
                .contains("create_task");
    }

    private List<LlmClient.ToolDefinition> buildConversationTools(List<BaseTool> tools, List<String> availableSkillNames) {
        List<String> names = availableSkillNames == null ? List.of() : List.copyOf(availableSkillNames);
        return tools.stream()
                .map(tool -> tool.build(names))
                .flatMap(Optional::stream)
                .toList();
    }
}
