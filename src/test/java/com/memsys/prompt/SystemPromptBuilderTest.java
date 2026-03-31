package com.memsys.prompt;

import com.memsys.memory.model.ReflectionResult;
import com.memsys.rag.RagService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptBuilderTest {

    private final SystemPromptBuilder builder = new SystemPromptBuilder();

    @Test
    void buildSystemPromptKeepsSectionOrderAndToolGuidance() {
        String prompt = builder.buildSystemPrompt(
                "agent guide content",
                Map.<String, Object>of("timezone", "Asia/Shanghai"),
                Map.<String, Object>of("tone", "concise"),
                new ReflectionResult(
                        true,
                        "PERSONALIZATION",
                        "需要结合用户历史偏好",
                        0.91d,
                        "优先检索用户偏好相关记忆",
                        List.of("USER_INSIGHT"),
                        List.of("personalization", "continuity")
                ),
                List.of(Map.<String, Object>of("timestamp", "2026-03-18 19:00:00", "message", "你好")),
                "用户偏好简洁直接。",
                List.of("debugging"),
                true,
                true,
                true,
                true,
                true,
                true,
                List.of("[到期] 例会 @ 2026-03-29 20:00"),
                "**Problem**: P\n**Solution**: S\n",
                List.of(new RagService.RelevantMemory("home_city", "用户住在上海", 0.91, Map.of())),
                null
        );

        assertSectionOrder(prompt,
                "## 1. 会话启动导航地图",
                "## 2. 用户交互元数据",
                "## 3. 助手回复偏好",
                "## 4. 最近对话内容",
                "## 5. 用户画像正文（User Insights）",
                "## 6. Skill 加载策略",
                "## 7. 相关案例（RAG Examples）",
                "## 8. 语义相关记忆（RAG Retrieved Context）"
        );

        assertThat(prompt).contains("load_skill(name)");
        assertThat(prompt).contains("search_rag(query)");
        assertThat(prompt).contains("run_shell(command, cwd)");
        assertThat(prompt).contains("run_shell_command(command, cwd)");
        assertThat(prompt).contains("run_python_script(script, args_json)");
        assertThat(prompt).contains("create_task(...)");
        assertThat(prompt).contains("## 3.5 记忆反思决策");
        assertThat(prompt).contains("needs_memory: true");
        assertThat(prompt).contains("memory_purpose: PERSONALIZATION");
        assertThat(prompt).contains("reason: 需要结合用户历史偏好");
        assertThat(prompt).contains("retrieval_hint: 优先检索用户偏好相关记忆");
        assertThat(prompt).contains("evidence_types: USER_INSIGHT");
        assertThat(prompt).contains("evidence_purposes: personalization, continuity");
        assertThat(prompt).contains("## 9. 相关任务上下文（Retrieved Tasks）");
        assertThat(prompt).contains("[到期] 例会 @ 2026-03-29 20:00");
        assertThat(prompt).doesNotContain("Agent.md");
    }

    @Test
    void buildSystemPromptShowsNoSkillMessageWhenSkillToolUnavailable() {
        String prompt = builder.buildSystemPrompt(
                "",
                null,
                null,
                null,
                List.<Map<String, Object>>of(),
                null,
                List.<String>of(),
                false,
                false,
                false,
                false,
                false,
                false,
                List.<String>of(),
                null,
                List.<RagService.RelevantMemory>of(),
                null
        );

        assertThat(prompt).contains("当前没有可加载的 skill");
        assertThat(prompt).doesNotContain("search_rag(query)");
    }

    @Test
    void buildSystemPromptShouldFallbackReflectionFieldsWhenInvalid() {
        String prompt = builder.buildSystemPrompt(
                "",
                null,
                null,
                new ReflectionResult(
                        true,
                        "",
                        "null",
                        Double.NaN,
                        "N/A",
                        List.of(),
                        List.of()
                ),
                List.<Map<String, Object>>of(),
                null,
                List.<String>of(),
                false,
                false,
                false,
                false,
                false,
                false,
                List.<String>of(),
                null,
                List.<RagService.RelevantMemory>of(),
                null
        );

        assertThat(prompt).contains("memory_purpose: CONTINUITY");
        assertThat(prompt).contains("reason: 需要调用长期记忆以保证回答质量。");
        assertThat(prompt).contains("confidence: 0.70");
        assertThat(prompt).contains("retrieval_hint: 优先检索与用户当前问题最相关的历史证据。");
    }

    @Test
    void buildSystemPromptShouldNormalizeOutOfRangeConfidence() {
        String slightOverflowPrompt = builder.buildSystemPrompt(
                "",
                null,
                null,
                new ReflectionResult(
                        true,
                        "CONTINUITY",
                        "需要记忆",
                        1.2d,
                        "优先检索相关证据",
                        List.of(),
                        List.of()
                ),
                List.<Map<String, Object>>of(),
                null,
                List.<String>of(),
                false,
                false,
                false,
                false,
                false,
                false,
                List.<String>of(),
                null,
                List.<RagService.RelevantMemory>of(),
                null
        );
        assertThat(slightOverflowPrompt).contains("confidence: 1.00");

        String percentagePrompt = builder.buildSystemPrompt(
                "",
                null,
                null,
                new ReflectionResult(
                        true,
                        "CONTINUITY",
                        "需要记忆",
                        87d,
                        "优先检索相关证据",
                        List.of(),
                        List.of()
                ),
                List.<Map<String, Object>>of(),
                null,
                List.<String>of(),
                false,
                false,
                false,
                false,
                false,
                false,
                List.<String>of(),
                null,
                List.<RagService.RelevantMemory>of(),
                null
        );
        assertThat(percentagePrompt).contains("confidence: 0.87");
    }

    private void assertSectionOrder(String prompt, String... sections) {
        int lastIndex = -1;
        for (String section : sections) {
            int index = prompt.indexOf(section);
            assertThat(index).as("section not found: " + section).isGreaterThan(-1);
            assertThat(index).as("section order mismatch: " + section).isGreaterThan(lastIndex);
            lastIndex = index;
        }
    }
}
