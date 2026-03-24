package com.memsys.cli;

import com.memsys.memory.model.Memory;
import com.memsys.memory.model.MemoryEvidenceTrace;
import com.memsys.memory.MemoryExtractor;
import com.memsys.memory.MemoryManager;
import com.memsys.memory.MemoryWriteService;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.llm.LlmExtractionService;
import com.memsys.llm.LlmDtos.ExampleItem;
import com.memsys.llm.LlmDtos.SkillGenerationResult;
import com.memsys.rag.RagService;
import com.memsys.skill.SkillService;
import com.memsys.skill.SkillService.SkillFile;
import com.memsys.task.ScheduledTaskService;
import com.memsys.task.model.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.jline.reader.Completer;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Binding;
import org.jline.reader.Reference;
import org.jline.reader.impl.DefaultParser;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * CLI 主循环 + 命令路由 + 展示逻辑。
 * 从 MemoryBoxApplication 拆出，让启动类保持纯净。
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "cli.enabled", havingValue = "true", matchIfMissing = true)
public class CliRunner implements CommandLineRunner {

    private static final String USER_PROMPT = "❯ ";
    private static final String INPUT_HINT = "Type your message or @path/to/file, /help for commands";
    private static final int PREVIEW_LIMIT = 96;
    private static final int SPINNER_PHRASE_ROTATE_SECONDS = 2;
    private static final DateTimeFormatter STATUS_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_DIM = "\u001B[2m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_MAGENTA = "\u001B[35m";
    private static final String ANSI_WHITE = "\u001B[37m";
    private static final String ANSI_CLEAR_LINE = "\u001B[2K";
    private static final String[] THINKING_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String[] THINKING_PHRASES = {"正在组织记忆上下文", "正在检索相关记忆", "正在生成回答"};
    private static final String[] HEADER_ICON = {
            "▝▜▄  ",
            "  ▝▜▄",
            " ▗▟▀ ",
            "▝▀   "
    };
    private static final String[] HEADER_ICON_MAC = {
            "▝▜▄  ",
            "  ▝▜▄",
            "  ▗▟▀",
            "▗▟▀  "
    };
    private static final Set<String> SUPPORTED_THEMES = Set.of("classic", "ocean", "mono");
    private static final String SLASH_AUTO_COMPLETE_WIDGET = "slash-auto-complete";
    private static final Map<String, String> COMMAND_DESCRIPTIONS = buildCommandDescriptions();
    private static final List<String> COMPLETABLE_COMMANDS = List.copyOf(COMMAND_DESCRIPTIONS.keySet());

    private final ConversationCli conversationCli;
    private final MemoryManager memoryManager;
    private final MemoryStorage storage;
    private final MemoryExtractor memoryExtractor;
    private final MemoryWriteService memoryWriteService;
    private final RagService ragService;
    private final SkillService skillService;
    private final LlmExtractionService llmExtractionService;
    private final ScheduledTaskService scheduledTaskService;

    @Value("${memory.max-slots:100}")
    private int maxSlots;
    @Value("${memory.days-unaccessed:30}")
    private int daysUnaccessed;
    @Value("${rag.enabled:true}")
    private boolean ragEnabled;
    @Value("${rag.min-similarity-score:0.35}")
    private double ragMinScore;
    @Value("${rag.max-search-results:5}")
    private int ragMaxResults;
    @Value("${llm.model-name:gpt-4}")
    private String modelName;
    @Value("${cli.theme:classic}")
    private String cliTheme;
    @Value("${cli.show-footer:true}")
    private boolean showFooter;
    @Value("${cli.show-shortcuts-panel:false}")
    private boolean showShortcutsPanel;
    @Value("${spring.application.name:memory-box}")
    private String appName;

    private boolean temporaryMode;
    private final List<String> startupNotes = new ArrayList<>();
    private final List<String> startupWarnings = new ArrayList<>();

    public CliRunner(
            ConversationCli conversationCli,
            MemoryManager memoryManager,
            MemoryStorage storage,
            MemoryExtractor memoryExtractor,
            MemoryWriteService memoryWriteService,
            RagService ragService,
            SkillService skillService,
            LlmExtractionService llmExtractionService,
            ScheduledTaskService scheduledTaskService
    ) {
        this.conversationCli = conversationCli;
        this.memoryManager = memoryManager;
        this.storage = storage;
        this.memoryExtractor = memoryExtractor;
        this.memoryWriteService = memoryWriteService;
        this.ragService = ragService;
        this.skillService = skillService;
        this.llmExtractionService = llmExtractionService;
        this.scheduledTaskService = scheduledTaskService;
    }

    @Override
    public void run(String... args) {
        startupNotes.clear();
        startupWarnings.clear();
        this.temporaryMode = false;
        for (String arg : args) {
            if ("--temporary".equals(arg) || "temp".equals(arg)) {
                this.temporaryMode = true;
                break;
            }
        }

        if (temporaryMode) {
            conversationCli.setTemporaryMode(true);
            startupWarnings.add("temporary 模式：不读取/写入长期记忆。");
        } else {
            loadCliUiSettingsFromMetadata();
        }

        // 启动时索引所有记忆到向量存储
        if (ragEnabled && !temporaryMode) {
            try {
                ragService.cleanupLegacyDocuments();
                ragService.indexAllMemories();
                startupNotes.add("RAG 索引已就绪。");
            } catch (Exception e) {
                log.warn("RAG 向量索引初始化失败，语义检索功能可能不可用", e);
                startupWarnings.add("RAG 索引初始化失败，语义检索暂不可用。");
            }
        }

        printWelcome(true);
        startCli();
    }

    // ========== CLI 主循环 ==========

    private void startCli() {
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader lineReader = buildLineReader(terminal);
            printPromptHint();
            while (true) {
                printPromptStatus();
                String input;
                try {
                    input = lineReader.readLine(style(USER_PROMPT, ANSI_BOLD, themePalette().accentColor())).trim();
                } catch (UserInterruptException e) {
                    System.out.println();
                    continue;
                } catch (EndOfFileException e) {
                    printSystem("输入流结束，会话关闭。");
                    return;
                }

                if (input.isEmpty()) {
                    continue;
                }

                if (input.startsWith("/")) {
                    if (!handleCommand(input, lineReader)) {
                        break;
                    }
                    continue;
                }

                try {
                    printUserMessage(input);
                    String response = processWithSpinner(input);
                    printSystem(response);
                } catch (Exception e) {
                    log.warn("处理消息失败", e);
                    printSystem("请求处理失败，请稍后重试。");
                }
            }
        } catch (IOException e) {
            log.error("Failed to initialize interactive terminal", e);
            printSystem("终端初始化失败，无法进入交互模式。");
        }
    }

    // ========== 命令路由 ==========

    private boolean handleCommand(String command, LineReader lineReader) {
        String[] parts = command.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/" -> showSlashCommandSuggestions("");
            case "/help" -> printWelcome();
            case "/memories" -> showMemories();
            case "/tasks" -> showScheduledTasks();
            case "/what-you-know" -> showWhatYouKnow();
            case "/theme" -> {
                if (parts.length == 1) {
                    showTheme();
                } else {
                    setTheme(parts[1]);
                }
            }
            case "/footer", "/statusline" -> {
                if (parts.length == 1) {
                    showFooterSetting();
                } else {
                    setFooter(parts[1]);
                }
            }
            case "/shortcuts" -> {
                if (parts.length == 1) {
                    toggleShortcutsPanel(null);
                } else {
                    toggleShortcutsPanel(parts[1]);
                }
            }
            case "/edit" -> {
                if (parts.length < 2) {
                    printSystem("用法: /edit <槽位名>");
                } else {
                    editMemory(parts[1], lineReader);
                }
            }
            case "/delete" -> {
                if (parts.length < 2) {
                    printSystem("用法: /delete <槽位名>");
                } else {
                    deleteMemory(parts[1]);
                }
            }
            case "/cleanup" -> cleanupOldMemories();
            case "/controls" -> {
                if (parts.length == 1) {
                    showControls();
                } else if (parts.length == 3) {
                    setControl(parts[1], parts[2]);
                } else {
                    printSystem("用法: /controls 或 /controls <项> <值>\n示例: /controls memories on");
                }
            }
            case "/memory-update" -> triggerMemoryUpdate();
            case "/search" -> {
                if (parts.length < 2) {
                    printSystem("用法: /search <关键词>");
                } else {
                    String query = command.substring("/search".length()).trim();
                    searchMemories(query);
                }
            }
            case "/rag-stats" -> showRagStats();
            case "/rag-rebuild" -> rebuildIndex();
            case "/skills" -> showSkills();
            case "/skill" -> {
                if (parts.length < 2) {
                    printSystem("用法: /skill <name>");
                } else {
                    showSkill(parts[1]);
                }
            }
            case "/skill-generate" -> triggerSkillGeneration();
            case "/skill-delete" -> {
                if (parts.length < 2) {
                    printSystem("用法: /skill-delete <name>");
                } else {
                    deleteSkill(parts[1]);
                }
            }
            case "/examples" -> showExamples();
            case "/example-extract" -> triggerExampleExtraction();
            case "/memory-debug" -> showMemoryDebug();
            case "/exit", "/quit" -> {
                printSystem("会话结束。");
                return false;
            }
            default -> {
                String suggestions = showSlashCommandSuggestions(cmd);
                if (suggestions == null) {
                    printSystem("未知命令: " + cmd + "\n输入 /help 查看可用命令。");
                }
            }
        }

        return true;
    }

    // ========== 展示方法 ==========

    private void printWelcome() {
        printWelcome(false);
    }

    private void printWelcome(boolean includeStartupStatus) {
        ThemePalette palette = themePalette();
        String ragFlag = ragEnabled ? style("on", palette.okColor()) : style("off", palette.warnColor());
        String modeFlag = temporaryMode ? style("temporary", palette.warnColor()) : style("memory", palette.okColor());

        printGeminiLikeHeader(palette);
        System.out.println(style("ready · mode=" + modeFlag + " · rag=" + ragFlag + " · theme=" + normalizeTheme(cliTheme), ANSI_DIM));
        if (includeStartupStatus) {
            printStartupBanner();
        }

        printSystem(buildWelcomeHelpText());
    }

    private String buildWelcomeHelpText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Basics:\n")
                .append("- 直接输入消息开始对话\n")
                .append("- 使用 @path 说明文件/目录上下文（仅作提示）\n")
                .append("- 使用 / 命令执行管理操作\n\n")
                .append("Commands:\n");

        COMMAND_DESCRIPTIONS.forEach((command, description) ->
                sb.append(command).append("  ").append(description).append("\n"));
        return sb.toString().trim();
    }

    private void printStartupBanner() {
        if (startupNotes.isEmpty() && startupWarnings.isEmpty()) {
            return;
        }

        StringBuilder body = new StringBuilder();
        body.append("Session Status");
        for (String note : startupNotes) {
            body.append("\n- ").append(note);
        }
        for (String warning : startupWarnings) {
            body.append("\n- ").append(warning);
        }
        printBanner(body.toString(), !startupWarnings.isEmpty());
    }

    private void printBanner(String content, boolean warning) {
        String[] lines = content.replace("\r\n", "\n").split("\n", -1);
        int maxWidth = 0;
        for (String line : lines) {
            maxWidth = Math.max(maxWidth, line.length());
        }

        String borderColor = warning ? themePalette().warnColor() : themePalette().accentColor();
        String horizontal = "─".repeat(maxWidth + 2);
        System.out.println(style("╭" + horizontal + "╮", borderColor));
        for (String line : lines) {
            String padded = line + " ".repeat(Math.max(0, maxWidth - line.length()));
            System.out.println(style("│", borderColor) + " " + padded + " " + style("│", borderColor));
        }
        System.out.println(style("╰" + horizontal + "╯", borderColor));
        System.out.println();
    }

    private String showSlashCommandSuggestions(String rawPrefix) {
        String prefix = rawPrefix == null ? "" : rawPrefix.trim().toLowerCase(Locale.ROOT);
        if (!prefix.startsWith("/")) {
            return null;
        }

        List<Map.Entry<String, String>> matches = COMMAND_DESCRIPTIONS.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        if ("/".equals(prefix) || prefix.isBlank()) {
            sb.append("斜杠命令补全：");
        } else {
            sb.append("命令补全（").append(prefix).append("）：");
        }
        for (Map.Entry<String, String> entry : matches) {
            sb.append("\n").append(entry.getKey()).append("  ").append(entry.getValue());
        }
        printSystem(sb.toString());
        return sb.toString();
    }

    private void printGeminiLikeHeader(ThemePalette palette) {
        String[] iconSet = "Apple_Terminal".equalsIgnoreCase(System.getenv("TERM_PROGRAM"))
                ? HEADER_ICON_MAC
                : HEADER_ICON;
        String cliName = friendlyAppName() + " CLI";
        String version = resolveAppVersion();
        String[] sideTexts = {
                style(cliName, ANSI_BOLD),
                style("v" + version + " · memory-first conversation system", ANSI_DIM),
                style("focus: long-term memory dialogue, not a general agent", ANSI_DIM),
                style("type /help for all commands", ANSI_DIM)
        };
        String[] iconColors = {
                palette.accentColor(),
                palette.assistantColor(),
                palette.userColor(),
                palette.accentColor()
        };

        System.out.println();
        for (int i = 0; i < iconSet.length; i++) {
            String icon = style(iconSet[i], iconColors[i]);
            System.out.println(icon + "  " + sideTexts[i]);
        }
        System.out.println();
    }

    private String resolveAppVersion() {
        String implVersion = CliRunner.class.getPackage().getImplementationVersion();
        if (implVersion == null || implVersion.isBlank()) {
            return "dev";
        }
        return implVersion;
    }

    private String friendlyAppName() {
        String raw = appName == null ? "" : appName.trim();
        if (raw.isBlank()) {
            return "Memory Box";
        }

        String[] parts = raw.split("[-_\\s]+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(" ");
            }
            String lower = part.toLowerCase(Locale.ROOT);
            out.append(Character.toUpperCase(lower.charAt(0)));
            if (lower.length() > 1) {
                out.append(lower.substring(1));
            }
        }
        return out.length() == 0 ? "Memory Box" : out.toString();
    }

    private void showMemories() {
        Map<String, Memory> memories = memoryManager.listAllMemories();
        if (memories.isEmpty()) {
            printSystem("暂无记忆。");
            return;
        }

        List<Map.Entry<String, Memory>> sorted = memories.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("记忆列表（").append(sorted.size()).append("）");
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Memory> entry = sorted.get(i);
            Memory memory = entry.getValue();
            sb.append("\n")
                    .append(i + 1)
                    .append(". [")
                    .append(entry.getKey())
                    .append("] ")
                    .append(truncate(memory.getContent(), PREVIEW_LIMIT))
                    .append("\n   ")
                    .append("type=").append(memory.getMemoryType())
                    .append(" | source=").append(memory.getSource())
                    .append(" | hit=").append(memory.getHitCount())
                    .append(" | last=").append(memory.getLastAccessed());
        }
        printSystem(sb.toString());
    }

    private void showScheduledTasks() {
        List<ScheduledTask> tasks = scheduledTaskService.listTasks(100);
        if (tasks.isEmpty()) {
            printSystem("暂无定时任务。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("定时任务列表（").append(tasks.size()).append("）");
        for (int i = 0; i < tasks.size(); i++) {
            ScheduledTask task = tasks.get(i);
            String dueAt = task.getDueAt() == null ? "unknown" : task.getDueAt().toString();
            String status = task.getStatus() == null ? "unknown" : task.getStatus();
            String taskId = task.getId() == null ? "-" : task.getId();
            String shortId = taskId.substring(0, Math.min(8, taskId.length()));
            sb.append("\n")
                    .append(i + 1)
                    .append(". [")
                    .append(shortId)
                    .append("] ")
                    .append(task.getTitle() == null ? "(untitled)" : task.getTitle())
                    .append("\n   ")
                    .append("status=").append(status)
                    .append(" | due=").append(dueAt);
            if (task.getDetail() != null && !task.getDetail().isBlank()) {
                sb.append(" | detail=").append(truncate(task.getDetail(), PREVIEW_LIMIT));
            }
            if (task.getExecuteCommand() != null && !task.getExecuteCommand().isBlank()) {
                sb.append("\n   ").append("command=").append(truncate(task.getExecuteCommand(), PREVIEW_LIMIT));
            }
            if (task.getExecutionStatus() != null && !task.getExecutionStatus().isBlank()) {
                sb.append("\n   ").append("exec_status=").append(task.getExecutionStatus());
                if (task.getExecutionExitCode() != null) {
                    sb.append(" | exit_code=").append(task.getExecutionExitCode());
                }
            }
        }
        printSystem(sb.toString());
    }

    private void showWhatYouKnow() {
        String narrative = storage.readUserInsightsNarrative();
        Map<String, Memory> allMemories = memoryManager.listAllMemories();

        if (allMemories.isEmpty() && (narrative == null || narrative.isBlank()
                || "当前还没有形成稳定的长期用户画像。".equals(narrative.trim()))) {
            printSystem("我当前还没有记住你的信息。");
            return;
        }

        printSystem("当前用户画像：\n" + narrative);
    }

    private void editMemory(String slotName, LineReader lineReader) {
        Memory memory = memoryManager.getMemory(slotName);
        if (memory == null) {
            printSystem("未找到记忆: " + slotName);
            return;
        }

        printSystem("编辑槽位: " + slotName + "\n当前内容: " + memory.getContent() + "\n请输入新内容（回车取消）。");
        String newContent;
        try {
            newContent = lineReader.readLine(style(USER_PROMPT, ANSI_BOLD, themePalette().accentColor())).trim();
        } catch (UserInterruptException | EndOfFileException e) {
            printSystem("已取消编辑。");
            return;
        }

        if (newContent.isEmpty()) {
            printSystem("已取消编辑。");
            return;
        }

        memoryManager.editMemory(slotName, newContent);
        printSystem("已更新记忆: " + slotName);
    }

    private void deleteMemory(String slotName) {
        if (memoryManager.getMemory(slotName) == null) {
            printSystem("未找到记忆: " + slotName);
            return;
        }
        memoryManager.deleteMemory(slotName);
        printSystem("已删除记忆: " + slotName);
    }

    private void cleanupOldMemories() {
        List<String> deleted = memoryManager.cleanupOldMemories(maxSlots, daysUnaccessed);
        if (deleted.isEmpty()) {
            printSystem("无需清理。");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已清理 ").append(deleted.size())
                .append(" 条记忆（阈值=").append(daysUnaccessed)
                .append(" 天，最大槽位=").append(maxSlots).append("）");
        int previewCount = Math.min(5, deleted.size());
        sb.append("\n清理槽位: ").append(String.join(", ", deleted.subList(0, previewCount)));
        if (deleted.size() > previewCount) {
            sb.append(" ...");
        }
        printSystem(sb.toString());
    }

    @SuppressWarnings("unchecked")
    private void showControls() {
        Map<String, Object> metadata = storage.readMetadata();
        Map<String, Object> globalControls = (Map<String, Object>) metadata.get("global_controls");

        boolean useSavedMemories = globalControls == null || (boolean) globalControls.getOrDefault("use_saved_memories", true);
        boolean useChatHistory = globalControls == null || (boolean) globalControls.getOrDefault("use_chat_history", true);

        printSystem("全局控制：\nmemories=" + onOff(useSavedMemories) + "\nhistory=" + onOff(useChatHistory));
    }

    @SuppressWarnings("unchecked")
    private void setControl(String controlName, String value) {
        Map<String, Object> metadata = storage.readMetadata();
        Map<String, Object> globalControls = (Map<String, Object>) metadata.getOrDefault("global_controls", new HashMap<>());

        Boolean boolValue = parseOnOff(value);
        if (boolValue == null) {
            printSystem("控制值仅支持: on/off/true/false");
            return;
        }

        switch (controlName.toLowerCase()) {
            case "use_memories", "memories" -> {
                globalControls.put("use_saved_memories", boolValue);
                metadata.put("global_controls", globalControls);
                storage.writeMetadata(metadata);
                printSystem("已设置 memories=" + onOff(boolValue));
            }
            case "use_history", "history" -> {
                globalControls.put("use_chat_history", boolValue);
                metadata.put("global_controls", globalControls);
                storage.writeMetadata(metadata);
                printSystem("已设置 history=" + onOff(boolValue));
            }
            default -> {
                printSystem("未知控制项: " + controlName + "\n可用控制项: memories, history");
            }
        }
    }

    private void showTheme() {
        printSystem("当前主题: " + normalizeTheme(cliTheme) + "\n可用主题: " + String.join(", ", SUPPORTED_THEMES));
    }

    private void setTheme(String requestedTheme) {
        String normalized = normalizeTheme(requestedTheme);
        if (!SUPPORTED_THEMES.contains(normalized)) {
            printSystem("未知主题: " + requestedTheme + "\n可用主题: " + String.join(", ", SUPPORTED_THEMES));
            return;
        }
        this.cliTheme = normalized;
        persistCliUiSettingsIfAllowed();
        printSystem("已切换主题: " + normalized + (temporaryMode ? "（临时会话未持久化）" : ""));
    }

    private void showFooterSetting() {
        printSystem("statusline=" + onOff(showFooter)
                + "\n别名: /footer 或 /statusline"
                + "\n用法: /footer <on|off>");
    }

    private void setFooter(String value) {
        Boolean flag = parseOnOff(value);
        if (flag == null) {
            printSystem("footer 仅支持 on/off/true/false");
            return;
        }
        this.showFooter = flag;
        persistCliUiSettingsIfAllowed();
        printSystem("已设置 footer=" + onOff(flag) + (temporaryMode ? "（临时会话未持久化）" : ""));
    }

    private void toggleShortcutsPanel(String value) {
        if (value == null || value.isBlank()) {
            this.showShortcutsPanel = !showShortcutsPanel;
        } else {
            Boolean flag = parseOnOff(value);
            if (flag == null) {
                printSystem("shortcuts 仅支持 on/off/true/false");
                return;
            }
            this.showShortcutsPanel = flag;
        }

        persistCliUiSettingsIfAllowed();
        printSystem("shortcuts=" + onOff(showShortcutsPanel)
                + (temporaryMode ? "（临时会话未持久化）" : ""));
        if (showShortcutsPanel) {
            printShortcutsPanel();
        }
    }

    private void triggerMemoryUpdate() {
        try {
            LocalDateTime startDate = LocalDateTime.now().minusDays(7);
            List<Map<String, Object>> recentHistory = storage.getHistory(startDate, null);

            if (recentHistory.isEmpty()) {
                printSystem("最近 7 天无对话历史，跳过提取。");
                return;
            }

            List<Map<String, Object>> insights = memoryExtractor.extractUserInsights(recentHistory, "manual");
            int savedCount = 0;
            for (Map<String, Object> insight : insights) {
                String slotName = safeString(insight.get("slot_name"));
                String content = safeString(insight.get("content"));
                if (slotName.isBlank() || content.isBlank()) {
                    continue;
                }
                memoryWriteService.saveMemory(
                        slotName,
                        content,
                        Memory.MemoryType.USER_INSIGHT,
                        Memory.SourceType.IMPLICIT,
                        safeString(insight.getOrDefault("confidence", "medium"))
                );
                savedCount++;
            }

            printSystem("记忆提取完成：候选 " + insights.size() + " 条，写入 " + savedCount + " 条。");
        } catch (Exception e) {
            log.warn("Manual memory update failed", e);
            printSystem("记忆提取失败，请检查模型配置或网络。");
        }
    }

    // ========== Skill 相关 ==========

    private void showSkills() {
        List<SkillFile> skills = skillService.listSkills();
        if (skills.isEmpty()) {
            printSystem("暂无 skill 文件。使用 /skill-generate 从对话中生成。");
            return;
        }
        StringBuilder sb = new StringBuilder("Skills（").append(skills.size()).append("）");
        for (SkillFile skill : skills) {
            sb.append("\n- ").append(skill.name())
              .append(" (").append(skill.content().length()).append(" chars)");
        }
        printSystem(sb.toString());
    }

    private void showSkill(String name) {
        skillService.readSkill(name).ifPresentOrElse(
            skill -> printSystem("Skill: " + skill.name() + "\n" + skill.content()),
            () -> printSystem("未找到 skill: " + name)
        );
    }

    private void triggerSkillGeneration() {
        try {
            LocalDateTime startDate = LocalDateTime.now().minusDays(7);
            List<Map<String, Object>> recentHistory = storage.getHistory(startDate, null);
            if (recentHistory.isEmpty()) {
                printSystem("最近 7 天无对话历史，无法生成 skill。");
                return;
            }
            Optional<SkillGenerationResult> result = llmExtractionService.generateSkill(recentHistory);
            if (result.isPresent()) {
                SkillGenerationResult skill = result.get();
                skillService.saveSkill(skill.skill_name(), skill.skill_content());
                printSystem("已生成 skill: " + skill.skill_name());
            } else {
                printSystem("未从对话中识别出可复用的方法论。");
            }
        } catch (Exception e) {
            log.warn("Skill generation failed", e);
            printSystem("Skill 生成失败。");
        }
    }

    private void deleteSkill(String name) {
        if (skillService.deleteSkill(name)) {
            printSystem("已删除 skill: " + name);
        } else {
            printSystem("未找到 skill: " + name);
        }
    }

    // ========== Example 相关 ==========

    private void showExamples() {
        if (!ragEnabled) {
            printSystem("RAG 功能未启用（rag.enabled=false）。");
            return;
        }
        Map<String, Object> stats = ragService.getStatistics();
        long exampleCount = (long) stats.getOrDefault("example_documents", 0L);
        printSystem("向量库中共有 " + exampleCount + " 个 example 文档。");
    }

    private void triggerExampleExtraction() {
        try {
            LocalDateTime startDate = LocalDateTime.now().minusDays(7);
            List<Map<String, Object>> recentHistory = storage.getHistory(startDate, null);
            if (recentHistory.isEmpty()) {
                printSystem("最近 7 天无对话历史，无法提取 example。");
                return;
            }
            List<ExampleItem> examples = llmExtractionService.extractExamples(recentHistory);
            if (examples.isEmpty()) {
                printSystem("未从对话中识别出 problem/solution 对。");
                return;
            }
            for (ExampleItem example : examples) {
                ragService.indexExample(example);
            }
            printSystem("已提取并索引 " + examples.size() + " 个 examples。");
        } catch (Exception e) {
            log.warn("Example extraction failed", e);
            printSystem("Example 提取失败。");
        }
    }

    // ========== Memory Debug ==========

    private void showMemoryDebug() {
        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        if (trace == null) {
            printSystem("暂无记忆反思记录。请先发送一条消息，再使用 /memory-debug 查看。");
            return;
        }
        printSystem(trace.buildDisplaySummary());
    }

    // ========== RAG 相关 ==========

    private void searchMemories(String query) {
        if (!ragEnabled) {
            printSystem("RAG 功能未启用（rag.enabled=false）。");
            return;
        }

        List<RagService.RelevantMemory> results;
        try {
            results = ragService.searchMemories(query, ragMaxResults, ragMinScore);
        } catch (Exception e) {
            log.warn("RAG search failed", e);
            printSystem("语义搜索失败，请稍后重试。");
            return;
        }

        if (results.isEmpty()) {
            printSystem("未找到与 \"" + query + "\" 相关的记忆。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("语义搜索结果：query=").append(query);
        for (int i = 0; i < results.size(); i++) {
            RagService.RelevantMemory mem = results.get(i);
            sb.append("\n")
                    .append(i + 1)
                    .append(". [")
                    .append(mem.getSlotName())
                    .append("] score=")
                    .append(Math.round(mem.getScore() * 100))
                    .append("%\n   ")
                    .append(truncate(mem.getContent(), PREVIEW_LIMIT));
        }
        printSystem(sb.toString());
    }

    private void rebuildIndex() {
        if (!ragEnabled) {
            printSystem("RAG 功能未启用（rag.enabled=false）。");
            return;
        }

        try {
            ragService.indexAllMemories();
            Map<String, Object> stats = ragService.getStatistics();
            printSystem("索引重建完成：total=" + stats.get("total_documents")
                    + ", memory=" + stats.get("memory_documents")
                    + ", conversation=" + stats.get("conversation_documents"));
        } catch (Exception e) {
            log.warn("RAG rebuild failed", e);
            printSystem("索引重建失败，请稍后重试。");
        }
    }

    private void showRagStats() {
        if (!ragEnabled) {
            printSystem("RAG 功能未启用（rag.enabled=false）。");
            return;
        }

        try {
            Map<String, Object> stats = ragService.getStatistics();
            printSystem("RAG 统计：\n"
                    + "total=" + stats.get("total_documents") + "\n"
                    + "memory=" + stats.get("memory_documents") + "\n"
                    + "conversation=" + stats.get("conversation_documents") + "\n"
                    + "example=" + stats.get("example_documents") + "\n"
                    + "minScore=" + Math.round(ragMinScore * 100) + "%\n"
                    + "maxResults=" + ragMaxResults);
        } catch (Exception e) {
            log.warn("RAG stats failed", e);
            printSystem("读取 RAG 统计失败，请稍后重试。");
        }
    }

    private void printSystem(String message) {
        if (message == null) {
            return;
        }

        printCard("assistant", themePalette().assistantColor(), message);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private Boolean parseOnOff(String value) {
        if (value == null) {
            return null;
        }
        if ("on".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("off".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return false;
        }
        return null;
    }

    private LineReader buildLineReader(Terminal terminal) throws IOException {
        Completer completer = buildCliCompleter();
        LineReader lineReader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("memory-box")
                .parser(new DefaultParser())
                .completer(completer)
                .build();
        lineReader.setOpt(LineReader.Option.AUTO_LIST);
        lineReader.setOpt(LineReader.Option.LIST_PACKED);

        Path historyPath = Paths.get(".memory", ".cli-history");
        Files.createDirectories(historyPath.getParent());
        if (Files.notExists(historyPath)) {
            Files.createFile(historyPath);
        }
        lineReader.setVariable(LineReader.HISTORY_FILE, historyPath);
        lineReader.setVariable(LineReader.HISTORY_SIZE, 1000);
        enableSlashAutoComplete(lineReader);
        return lineReader;
    }

    private Completer buildCliCompleter() {
        return (reader, parsedLine, candidates) -> {
            String word = Optional.ofNullable(parsedLine.word()).orElse("");
            String normalizedWord = word.toLowerCase(Locale.ROOT);

            if (normalizedWord.startsWith("/")) {
                COMMAND_DESCRIPTIONS.forEach((command, description) -> {
                    if (command.startsWith(normalizedWord)) {
                        candidates.add(new Candidate(
                                command,
                                command,
                                "slash commands",
                                description,
                                null,
                                null,
                                true
                        ));
                    }
                });
                return;
            }

            for (String candidate : buildCompletionWords()) {
                if (candidate.toLowerCase(Locale.ROOT).startsWith(normalizedWord)) {
                    candidates.add(new Candidate(candidate));
                }
            }
        };
    }

    private List<String> buildCompletionWords() {
        Set<String> words = new LinkedHashSet<>(COMPLETABLE_COMMANDS);
        words.addAll(List.of("memories", "history", "on", "off", "true", "false"));
        words.addAll(SUPPORTED_THEMES);
        words.addAll(skillService.listSkillNames());
        words.addAll(memoryManager.listAllMemories().keySet());
        return new ArrayList<>(words);
    }

    private void enableSlashAutoComplete(LineReader lineReader) {
        lineReader.getWidgets().put(SLASH_AUTO_COMPLETE_WIDGET, () -> {
            lineReader.callWidget(LineReader.SELF_INSERT);
            String currentBuffer = lineReader.getBuffer().toString();
            int cursor = lineReader.getBuffer().cursor();
            if (cursor == 1 && "/".equals(currentBuffer)) {
                lineReader.callWidget(LineReader.COMPLETE_WORD);
            }
            return true;
        });

        List<String> keymapNames = List.of(LineReader.MAIN, LineReader.EMACS, LineReader.VIINS);
        for (String keymapName : keymapNames) {
            KeyMap<Binding> keyMap = lineReader.getKeyMaps().get(keymapName);
            if (keyMap != null) {
                keyMap.bind(new Reference(SLASH_AUTO_COMPLETE_WIDGET), "/");
            }
        }
    }

    private void printPromptHint() {
        System.out.println(style(INPUT_HINT, ANSI_DIM));
        if (showShortcutsPanel) {
            printShortcutsPanel();
            return;
        }
        System.out.println(style("type / to auto-show command completion  ·  /shortcuts for cheatsheet", ANSI_DIM));
    }

    private void printPromptStatus() {
        String rag = ragEnabled ? "on" : "off";
        String mode = temporaryMode ? "temp" : "memory";
        String model = (modelName == null || modelName.isBlank()) ? "unknown" : modelName;
        String theme = normalizeTheme(cliTheme);
        String cwd = Optional.ofNullable(Paths.get("").toAbsolutePath().normalize().getFileName())
                .map(Path::toString)
                .orElse("/");
        String now = LocalDateTime.now().format(STATUS_TIME_FORMATTER);
        String status = "model " + model
                + " · mode " + mode
                + " · rag " + rag
                + " · theme " + theme
                + " · skills " + countSkills()
                + " · memories " + countMemories()
                + " · cwd " + cwd
                + " · " + now;
        System.out.println(style("┈ " + status, ANSI_DIM));
        if (showFooter) {
            System.out.println(style("/help /theme /footer /statusline /shortcuts /skills /memories /tasks /what-you-know /exit", ANSI_DIM));
        }
    }

    private void printShortcutsPanel() {
        System.out.println(style("┌ Shortcuts", ANSI_DIM));
        System.out.println(style("│ Tab            complete command/file", ANSI_DIM));
        System.out.println(style("│ Ctrl+R         search history", ANSI_DIM));
        System.out.println(style("│ Ctrl+C         cancel current line", ANSI_DIM));
        System.out.println(style("│ /shortcuts     toggle this panel", ANSI_DIM));
        System.out.println(style("│ /statusline    toggle footer/status line", ANSI_DIM));
        System.out.println(style("└ /help          full command list", ANSI_DIM));
    }

    private String style(String text, String... styles) {
        StringBuilder sb = new StringBuilder();
        for (String style : styles) {
            sb.append(style);
        }
        sb.append(text);
        sb.append(ANSI_RESET);
        return sb.toString();
    }

    private void printUserMessage(String message) {
        printCard("you", themePalette().userColor(), message);
    }

    private void printCard(String title, String titleColor, String message) {
        String[] lines = message.replace("\r\n", "\n").split("\n", -1);
        System.out.println(style("╭─ " + title, titleColor));
        for (String line : lines) {
            System.out.println("│ " + line);
        }
        System.out.println(style("╰─", titleColor));
        System.out.println();
    }

    private String processWithSpinner(String input) throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> conversationCli.processUserMessage(input));
        int frame = 0;
        long startedAt = System.nanoTime();
        try {
            while (!future.isDone()) {
                long elapsedSeconds = Duration.ofNanos(System.nanoTime() - startedAt).toSeconds();
                int phraseIndex = (int) ((elapsedSeconds / SPINNER_PHRASE_ROTATE_SECONDS) % THINKING_PHRASES.length);
                String spinnerText = THINKING_FRAMES[frame]
                        + " "
                        + THINKING_PHRASES[phraseIndex]
                        + " ("
                        + elapsedSeconds
                        + "s)";
                System.out.print("\r" + ANSI_CLEAR_LINE + style(spinnerText, ANSI_DIM));
                System.out.flush();
                frame = (frame + 1) % THINKING_FRAMES.length;
                Thread.sleep(90);
            }
            System.out.print("\r" + ANSI_CLEAR_LINE);
            System.out.flush();
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待模型响应被中断", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException("模型调用失败", cause);
        } finally {
            System.out.println();
        }
    }

    private int countSkills() {
        try {
            return skillService.listSkillNames().size();
        } catch (Exception e) {
            log.debug("Failed to count skills for status bar", e);
            return 0;
        }
    }

    private int countMemories() {
        try {
            return memoryManager.listAllMemories().size();
        } catch (Exception e) {
            log.debug("Failed to count memories for status bar", e);
            return 0;
        }
    }

    private ThemePalette themePalette() {
        String normalized = normalizeTheme(cliTheme);
        return switch (normalized) {
            case "mono" -> new ThemePalette(ANSI_WHITE, ANSI_WHITE, ANSI_WHITE, ANSI_WHITE, ANSI_YELLOW);
            case "ocean" -> new ThemePalette(ANSI_CYAN, ANSI_BLUE, ANSI_BLUE, ANSI_GREEN, ANSI_YELLOW);
            default -> new ThemePalette(ANSI_CYAN, ANSI_MAGENTA, ANSI_CYAN, ANSI_GREEN, ANSI_YELLOW);
        };
    }

    private String normalizeTheme(String raw) {
        if (raw == null || raw.isBlank()) {
            return "classic";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private void loadCliUiSettingsFromMetadata() {
        try {
            Map<String, Object> metadata = storage.readMetadata();
            Object uiSettingsObj = metadata.get("ui_settings");
            if (!(uiSettingsObj instanceof Map<?, ?> uiSettings)) {
                return;
            }

            Object persistedThemeObj = uiSettings.get("cli_theme");
            String persistedTheme = normalizeTheme(
                    persistedThemeObj == null ? cliTheme : String.valueOf(persistedThemeObj)
            );
            if (SUPPORTED_THEMES.contains(persistedTheme)) {
                this.cliTheme = persistedTheme;
            }

            Object persistedFooter = uiSettings.get("show_footer");
            if (persistedFooter instanceof Boolean b) {
                this.showFooter = b;
            }

            Object persistedShortcutsPanel = uiSettings.get("show_shortcuts_panel");
            if (persistedShortcutsPanel instanceof Boolean b) {
                this.showShortcutsPanel = b;
            }
        } catch (Exception e) {
            log.warn("Failed to load CLI UI settings from metadata; using defaults", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void persistCliUiSettingsIfAllowed() {
        if (temporaryMode) {
            return;
        }
        try {
            Map<String, Object> metadata = storage.readMetadata();
            Object uiSettingsObj = metadata.get("ui_settings");
            Map<String, Object> uiSettings =
                    (uiSettingsObj instanceof Map<?, ?> existing)
                            ? new HashMap<>((Map<String, Object>) existing)
                            : new HashMap<>();

            uiSettings.put("cli_theme", normalizeTheme(cliTheme));
            uiSettings.put("show_footer", showFooter);
            uiSettings.put("show_shortcuts_panel", showShortcutsPanel);
            metadata.put("ui_settings", uiSettings);
            storage.writeMetadata(metadata);
        } catch (Exception e) {
            log.warn("Failed to persist CLI UI settings", e);
        }
    }

    private static Map<String, String> buildCommandDescriptions() {
        LinkedHashMap<String, String> commands = new LinkedHashMap<>();
        commands.put("/help", "显示帮助与命令概览");
        commands.put("/memories", "查看全部记忆槽位");
        commands.put("/tasks", "查看定时任务");
        commands.put("/what-you-know", "查看当前用户画像正文");
        commands.put("/theme", "切换 CLI 主题");
        commands.put("/footer", "打开/关闭状态栏");
        commands.put("/statusline", "footer 的别名");
        commands.put("/shortcuts", "打开/关闭快捷键提示面板");
        commands.put("/edit", "编辑指定记忆槽位");
        commands.put("/delete", "删除指定记忆槽位");
        commands.put("/cleanup", "清理过旧记忆");
        commands.put("/controls", "查看或设置记忆/历史开关");
        commands.put("/memory-update", "手动触发记忆提取");
        commands.put("/search", "语义检索记忆");
        commands.put("/rag-stats", "查看 RAG 统计");
        commands.put("/rag-rebuild", "重建 RAG 索引");
        commands.put("/skills", "列出技能文件");
        commands.put("/skill", "查看单个技能内容");
        commands.put("/skill-generate", "从历史生成技能");
        commands.put("/skill-delete", "删除技能");
        commands.put("/examples", "查看 example 文档数量");
        commands.put("/example-extract", "提取并索引 examples");
        commands.put("/memory-debug", "展示最近一轮记忆反思与证据使用");
        commands.put("/exit", "退出会话");
        commands.put("/quit", "退出会话");
        return Collections.unmodifiableMap(commands);
    }

    private record ThemePalette(
            String assistantColor,
            String userColor,
            String accentColor,
            String okColor,
            String warnColor
    ) {
    }
}
