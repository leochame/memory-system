package com.memsys.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.memsys.llm.LlmClient;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 受控 Python 脚本执行工具：
 * - 仅允许执行 scripts 目录下的 .py 文件
 * - 参数通过 JSON 字符串数组传入，避免 shell 拼接
 */
@Component
public class PythonScriptTool extends BaseTool {

    private static final List<String> FORBIDDEN_ARG_PATTERNS = List.of("\n", "\r", "\u0000");

    private final boolean enabled;
    private final String pythonInterpreter;
    private final int timeoutSeconds;
    private final int maxOutputChars;
    private final Path scriptsRoot;

    public PythonScriptTool(
            @Value("${tool.python.enabled:true}") boolean enabled,
            @Value("${tool.python.interpreter:python3}") String pythonInterpreter,
            @Value("${tool.python.timeout-seconds:20}") int timeoutSeconds,
            @Value("${tool.python.max-output-chars:12000}") int maxOutputChars,
            @Value("${tool.python.scripts-dir:scripts}") String scriptsDir
    ) {
        this.enabled = enabled;
        this.pythonInterpreter = pythonInterpreter == null ? "python3" : pythonInterpreter.trim();
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxOutputChars = Math.max(500, maxOutputChars);
        this.scriptsRoot = Paths.get(scriptsDir).toAbsolutePath().normalize();
    }

    @Override
    public Optional<LlmClient.ToolDefinition> build(List<String> availableSkillNames) {
        if (!enabled) {
            return Optional.empty();
        }

        ToolSpecification spec = ToolSpecification.builder()
                .name("run_python_script")
                .description("执行 scripts 目录中的 Python 脚本。仅允许 .py 文件，参数必须使用 JSON 字符串数组。")
                .addParameter(
                        "script",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("脚本路径（相对 scripts 目录），例如: data/process.py")
                )
                .addParameter(
                        "args_json",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选，JSON 字符串数组参数，例如: [\"--date\",\"2026-03-20\"]")
                )
                .build();
        return Optional.of(new LlmClient.ToolDefinition(spec, this::execute));
    }

    private String execute(ToolExecutionRequest request) {
        String script = stringArg(request, "script");
        String argsJson = stringArg(request, "args_json");

        if (script.isBlank()) {
            return "run_python_script 调用失败：缺少参数 script。";
        }
        if (pythonInterpreter.isBlank()) {
            return "run_python_script 调用失败：python 解释器未配置。";
        }

        Path scriptPath = resolveScriptPath(script);
        if (scriptPath == null) {
            return "run_python_script 拒绝执行：脚本路径非法（必须位于 scripts 目录内且为 .py 文件）。";
        }
        if (!Files.exists(scriptPath) || !Files.isRegularFile(scriptPath)) {
            return "run_python_script 调用失败：脚本不存在 -> " + scriptPath;
        }

        List<String> args = parseArgs(argsJson);
        if (args == null) {
            return "run_python_script 调用失败：args_json 必须为 JSON 字符串数组。";
        }
        if (!areArgsSafe(args)) {
            return "run_python_script 拒绝执行：参数包含非法控制字符。";
        }

        List<String> command = new ArrayList<>();
        command.add(pythonInterpreter);
        command.add(scriptPath.toString());
        command.addAll(args);

        try {
            Process process = new ProcessBuilder(command)
                    .directory(scriptsRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "run_python_script 执行超时（" + timeoutSeconds + "s）。";
            }

            String output = readProcessOutput(process.getInputStream());
            int exitCode = process.exitValue();
            log.info("Executed tool run_python_script(exit={}, script='{}', args={})",
                    exitCode, scriptPath, args);

            return "exit_code=" + exitCode
                    + "\nscript=" + scriptsRoot.relativize(scriptPath)
                    + "\noutput:\n" + truncate(output, maxOutputChars);
        } catch (Exception e) {
            log.warn("run_python_script execution failed: script={}, args={}", scriptPath, args, e);
            return "run_python_script 执行失败：" + e.getMessage();
        }
    }

    private Path resolveScriptPath(String script) {
        Path path = scriptsRoot.resolve(script).normalize();
        if (!path.startsWith(scriptsRoot)) {
            return null;
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!fileName.endsWith(".py")) {
            return null;
        }
        return path;
    }

    private List<String> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(argsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    private boolean areArgsSafe(List<String> args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            String value = arg == null ? "" : arg;
            for (String pattern : FORBIDDEN_ARG_PATTERNS) {
                if (value.contains(pattern)) {
                    return false;
                }
            }
        }
        return true;
    }

    private String readProcessOutput(InputStream inputStream) throws Exception {
        try (inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            inputStream.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}

