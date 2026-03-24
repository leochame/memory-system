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

class PythonScriptToolTest {

    @TempDir
    Path tempDir;

    @Test
    void buildShouldExposeRunPythonScriptWhenEnabled() {
        PythonScriptTool tool = new PythonScriptTool(
                true,
                "python3",
                20,
                12000,
                tempDir.toString()
        );

        Optional<LlmClient.ToolDefinition> definition = tool.build(List.of());
        assertThat(definition).isPresent();
        assertThat(definition.orElseThrow().specification().name()).isEqualTo("run_python_script");
    }

    @Test
    void executeShouldRejectScriptPathTraversal() {
        PythonScriptTool tool = new PythonScriptTool(
                true,
                "python3",
                20,
                12000,
                tempDir.toString()
        );

        LlmClient.ToolDefinition definition = tool.build(List.of()).orElseThrow();
        String result = definition.executor().apply(ToolExecutionRequest.builder()
                .id("1")
                .name("run_python_script")
                .arguments("{\"script\":\"../hack.py\"}")
                .build());

        assertThat(result).contains("拒绝执行");
    }

    @Test
    void executeShouldRejectInvalidArgsJson() {
        try {
            Files.writeString(tempDir.resolve("demo.py"), "print('ok')");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        PythonScriptTool tool = new PythonScriptTool(
                true,
                "python3",
                20,
                12000,
                tempDir.toString()
        );

        LlmClient.ToolDefinition definition = tool.build(List.of()).orElseThrow();
        String result = definition.executor().apply(ToolExecutionRequest.builder()
                .id("2")
                .name("run_python_script")
                .arguments("{\"script\":\"demo.py\",\"args_json\":\"invalid-json\"}")
                .build());

        assertThat(result).contains("args_json 必须为 JSON 字符串数组");
    }
}
