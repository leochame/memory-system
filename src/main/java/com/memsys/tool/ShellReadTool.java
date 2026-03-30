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
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 只读 shell 查询工具。
 */
@Component
public class ShellReadTool extends BaseTool {

    private static final Set<String> ALLOWED_COMMANDS = Set.of(
            "rg", "grep", "sed", "head", "tail", "ls", "find", "cat", "wc"
    );
    private static final List<String> FORBIDDEN_PATTERNS = List.of(
            "|", ";", "&&", "||", ">", "<", "`", "$(", "\n", "\r", "\u0000"
    );

    private final boolean enabled;
    private final int timeoutSeconds;
    private final int maxOutputChars;
    private final Path workspaceRoot;

    public ShellReadTool(
            @Value("${tool.shell.enabled:true}") boolean enabled,
            @Value("${tool.shell.timeout-seconds:8}") int timeoutSeconds,
            @Value("${tool.shell.max-output-chars:6000}") int maxOutputChars,
            @Value("${tool.shell.workspace-root:.}") String workspaceRoot
    ) {
        this.enabled = enabled;
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
                .name("run_shell")
                .description("执行只读 shell 命令查询文件内容。仅允许命令: rg, grep, sed, head, tail, ls, find, cat, wc。")
                .addParameter(
                        "command",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("只读查询命令，如: rg -n \"Memory\" src/main/java")
                )
                .addParameter(
                        "cwd",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("可选，工作目录（相对 workspace-root）")
                )
                .build();
        return Optional.of(new LlmClient.ToolDefinition(spec, this::execute));
    }

    private String execute(ToolExecutionRequest request) {
        String command = stringArg(request, "command");
        String cwdArg = stringArg(request, "cwd");

        if (command.isBlank()) {
            return "run_shell 调用失败：缺少参数 command。";
        }
        if (!isCommandAllowed(command)) {
            return "run_shell 拒绝执行：仅允许只读命令 "
                    + String.join(", ", ALLOWED_COMMANDS)
                    + "，且不允许管道/重定向/命令拼接。";
        }

        Path workingDir = resolveWorkingDir(cwdArg);
        if (workingDir == null) {
            return "run_shell 调用失败：cwd 非法或越界。";
        }

        try {
            Process process = new ProcessBuilder("/bin/zsh", "-lc", command)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread outputReader = startOutputReader(process.getInputStream(), output, "shell-read-output-reader");
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputReader.join(2000);
                return "run_shell 执行超时（" + timeoutSeconds + "s）。";
            }
            outputReader.join(2000);

            String outputText = output.toString(StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            String limitedOutput = truncate(outputText, maxOutputChars);
            String relativeCwd = workspaceRoot.equals(workingDir)
                    ? "."
                    : workspaceRoot.relativize(workingDir).toString();

            log.info("Executed tool run_shell(exit={}, cwd='{}', command='{}')", exitCode, relativeCwd, command);
            return "exit_code=" + exitCode
                    + "\ncwd=" + relativeCwd
                    + "\noutput:\n" + limitedOutput;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "run_shell 执行失败：执行被中断。";
        } catch (Exception e) {
            log.warn("run_shell execution failed: {}", command, e);
            return "run_shell 执行失败：" + e.getMessage();
        }
    }

    private boolean isCommandAllowed(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isBlank()) {
            return false;
        }
        for (String pattern : FORBIDDEN_PATTERNS) {
            if (trimmed.contains(pattern)) {
                return false;
            }
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) {
            return false;
        }
        String cmd = parts[0];
        if (!ALLOWED_COMMANDS.contains(cmd)) {
            return false;
        }
        if ("sed".equals(cmd) && trimmed.contains(" -i")) {
            return false;
        }
        if ("find".equals(cmd) && trimmed.contains("-exec")) {
            return false;
        }
        if ("find".equals(cmd) && (
                trimmed.contains("-delete")
                        || trimmed.contains("-fprint")
                        || trimmed.contains("-fprintf")
        )) {
            return false;
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

    private Thread startOutputReader(InputStream inputStream, ByteArrayOutputStream output, String threadName) {
        Thread reader = new Thread(() -> {
            try (inputStream; output) {
                inputStream.transferTo(output);
            } catch (Exception ignored) {
                // Ignore reader exceptions; process exit status is the source of truth.
            }
        }, threadName);
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

}
