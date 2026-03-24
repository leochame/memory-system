package com.memsys.tool;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 可执行 shell 命令工具（允许写操作）。
 */
@Component
public class ShellCommandTool extends BaseTool {

    private static final List<String> FORBIDDEN_PATTERNS = List.of(
            "\u0000"
    );

    private final boolean enabled;
    private final String shell;
    private final int timeoutSeconds;
    private final int maxOutputChars;
    private final Path workspaceRoot;

    public ShellCommandTool(
            @Value("${tool.shell-command.enabled:true}") boolean enabled,
            @Value("${tool.shell-command.shell:/bin/zsh}") String shell,
            @Value("${tool.shell-command.timeout-seconds:30}") int timeoutSeconds,
            @Value("${tool.shell-command.max-output-chars:12000}") int maxOutputChars,
            @Value("${tool.shell-command.workspace-root:.}") String workspaceRoot
    ) {
        this.enabled = enabled;
        this.shell = shell == null || shell.isBlank() ? "/bin/zsh" : shell.trim();
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxOutputChars = Math.max(500, maxOutputChars);
        this.workspaceRoot = Paths.get(workspaceRoot).toAbsolutePath().normalize();
    }

    @Override
    public Optional<LlmClient.ToolDefinition> build(List<String> availableSkillNames) {
        if (!enabled) {
            return Optional.empty();
        }

        ToolSpecification spec = ToolSpecification.builder()
                .name("run_shell_command")
                .description("执行 shell 命令（可写）。仅在用户明确要求执行命令或修改文件时调用。")
                .addParameter(
                        "command",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("要执行的 shell 命令。")
                )
                .addParameter(
                        "cwd",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选，工作目录（相对 workspace-root）。")
                )
                .build();
        return Optional.of(new LlmClient.ToolDefinition(spec, this::execute));
    }

    private String execute(ToolExecutionRequest request) {
        String command = stringArg(request, "command");
        String cwdArg = stringArg(request, "cwd");

        if (command.isBlank()) {
            return "run_shell_command 调用失败：缺少参数 command。";
        }
        if (!isCommandSafe(command)) {
            return "run_shell_command 拒绝执行：命令包含非法控制字符。";
        }

        Path workingDir = resolveWorkingDir(cwdArg);
        if (workingDir == null) {
            return "run_shell_command 调用失败：cwd 非法或越界。";
        }

        try {
            Process process = new ProcessBuilder(shell, "-lc", command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "run_shell_command 执行超时（" + timeoutSeconds + "s）。";
            }

            String output = readProcessOutput(process.getInputStream());
            int exitCode = process.exitValue();
            String limitedOutput = truncate(output, maxOutputChars);
            String relativeCwd = workspaceRoot.equals(workingDir)
                    ? "."
                    : workspaceRoot.relativize(workingDir).toString();

            log.info("Executed tool run_shell_command(exit={}, cwd='{}', command='{}')", exitCode, relativeCwd, command);
            return "exit_code=" + exitCode
                    + "\ncwd=" + relativeCwd
                    + "\noutput:\n" + limitedOutput;
        } catch (Exception e) {
            log.warn("run_shell_command execution failed: {}", command, e);
            return "run_shell_command 执行失败：" + e.getMessage();
        }
    }

    private boolean isCommandSafe(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (trimmed.contains(pattern)) {
                return false;
            }
        }
        return true;
    }

    private Path resolveWorkingDir(String cwdArg) {
        Path requested = (cwdArg == null || cwdArg.isBlank())
                ? workspaceRoot
                : workspaceRoot.resolve(cwdArg).normalize();
        if (!requested.startsWith(workspaceRoot)) {
            return null;
        }
        if (!Files.exists(requested) || !Files.isDirectory(requested)) {
            return null;
        }
        return requested;
    }

    private String readProcessOutput(InputStream inputStream) throws Exception {
        try (inputStream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            inputStream.transferTo(output);
            return output.toString(StandardCharsets.UTF_8);
        }
    }
}
