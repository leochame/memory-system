package com.memsys.prompt;

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
                Map.of("timezone", "Asia/Shanghai"),
                Map.of("tone", "concise"),
                List.of(Map.of("timestamp", "2026-03-18 19:00:00", "message", "你好")),
                "用户偏好简洁直接。",
                List.of("debugging"),
                true,
                true,
                true,
                true,
                true,
                true,
                "**Problem**: P\n**Solution**: S\n",
                List.of(new RagService.RelevantMemory("home_city", "用户住在上海", 0.91, Map.of()))
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
        assertThat(prompt).doesNotContain("Agent.md");
    }

    @Test
    void buildSystemPromptShowsNoSkillMessageWhenSkillToolUnavailable() {
        String prompt = builder.buildSystemPrompt(
                "",
                null,
                null,
                List.of(),
                null,
                List.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                null,
                List.of()
        );

        assertThat(prompt).contains("当前没有可加载的 skill");
        assertThat(prompt).doesNotContain("search_rag(query)");
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
