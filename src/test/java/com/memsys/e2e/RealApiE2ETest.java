package com.memsys.e2e;

import com.memsys.cli.ConversationCli;
import com.memsys.llm.LlmClient;
import com.memsys.memory.model.Memory;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.rag.RagService;
import com.memsys.skill.SkillService;
import com.memsys.tool.BaseTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实模型/API 的手动 E2E 回归用例（默认不开启）。
 *
 * 启用方式：
 * 1) 设置 OPENAI_API_KEY
 * 2) 设置 MEMSYS_RUN_REAL_API_E2E=true
 * 3) 运行 mvn -Dtest=RealApiE2ETest test
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MEMSYS_RUN_REAL_API_E2E", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class RealApiE2ETest {

    private static final Path E2E_BASE_PATH = createE2eBasePath();

    @Autowired
    private ConversationCli conversationCli;
    @Autowired
    private SkillService skillService;
    @Autowired
    private MemoryStorage storage;
    @Autowired
    private RagService ragService;
    @Autowired
    private List<BaseTool> conversationTools;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("memory.base-path", () -> E2E_BASE_PATH.toString());
        registry.add("memory.async.enabled", () -> "false");
        registry.add("cli.enabled", () -> "false");
        registry.add("rag.enabled", () -> "true");
        registry.add("llm.api-key", () -> envOrDefault("OPENAI_API_KEY", ""));
        registry.add("llm.base-url", () -> envOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1"));
        registry.add("llm.model-name", () -> envOrDefault("OPENAI_MODEL", "gpt-4o-mini"));
    }

    @Test
    void realApiEndToEndConversationWorksWithToolExposureAndRagContext() {
        storage.writeMetadata(Map.of(
                "global_controls", Map.of(
                        "use_saved_memories", true,
                        "use_chat_history", true
                ),
                "assistant_preferences", Map.of(
                        "response_style", "concise"
                )
        ));

        skillService.saveSkill("debugging", "先复现，再定位，再验证。");

        Memory memory = new Memory();
        memory.setContent("用户不爱吃鱼");
        memory.setMemoryType(Memory.MemoryType.USER_INSIGHT);
        memory.setSource(Memory.SourceType.EXPLICIT);
        memory.setConfidence("high");
        memory.setHitCount(1);
        memory.setCreatedAt(LocalDateTime.now().minusDays(1));
        memory.setLastAccessed(LocalDateTime.now().minusHours(2));
        storage.writeUserInsight("food_preference", memory);
        ragService.indexMemory("food_preference", memory);

        List<String> availableSkills = skillService.listSkillNames();
        List<LlmClient.ToolDefinition> tools = conversationTools.stream()
                .map(tool -> tool.build(availableSkills))
                .flatMap(Optional::stream)
                .toList();
        assertThat(tools.stream().map(tool -> tool.specification().name()).toList())
                .contains("load_skill", "search_rag", "create_task");

        String reply = conversationCli.processUserMessage("我不爱吃什么？请一句话回答。");

        assertThat(reply).isNotBlank();
        assertThat(reply).doesNotContain("抱歉，我遇到了一些问题");

        assertThat(storage.getRecentMessages(10)).isNotEmpty();
        assertThat(storage.getRecentConversationTurns(2))
                .extracting(item -> item.get("role"))
                .contains("user", "assistant");
    }

    private static Path createE2eBasePath() {
        try {
            return Files.createTempDirectory("memsys-real-api-e2e-");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp directory for real-api e2e", e);
        }
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
