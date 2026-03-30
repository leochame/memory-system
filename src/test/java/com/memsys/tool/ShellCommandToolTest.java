package com.memsys.tool;

import com.memsys.llm.LlmClient;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ShellCommandToolTest {

    @TempDir
    Path tempDir;

    @Test
    void buildShouldExposeRunShellCommandWhenEnabled() {
        ShellCommandTool tool = new ShellCommandTool(
                true,
                "/bin/sh",
                10,
                5000,
                tempDir.toString()
        );

        Optional<LlmClient.ToolDefinition> definition = tool.build(List.of());
        assertThat(definition).isPresent();
        assertThat(definition.orElseThrow().specification().name()).isEqualTo("run_shell_command");
    }

    @Test
    void executeShouldRunShellCommandInWorkspace() throws Exception {
        ShellCommandTool tool = new ShellCommandTool(
                true,
                "/bin/sh",
                10,
                5000,
                tempDir.toString()
        );
        LlmClient.ToolDefinition definition = tool.build(List.of()).orElseThrow();

        String result;
        try (ToolRuntimeContext.Scope ignored = ToolRuntimeContext.bindTaskSourceContext("", "", "", true)) {
            result = definition.executor().apply(ToolExecutionRequest.builder()
                    .id("1")
                    .name("run_shell_command")
                    .arguments("{\"command\":\"echo cmd-ok > result.txt\",\"cwd\":\".\"}")
                    .build());
        }

        assertThat(result).contains("exit_code=0");
        assertThat(Files.readString(tempDir.resolve("result.txt"))).contains("cmd-ok");
    }
}
