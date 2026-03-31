package com.memsys.cli;

import com.memsys.memory.model.Memory;
import com.memsys.memory.model.MemoryEvidenceTrace;
import com.memsys.identity.UserIdentityService;
import com.memsys.identity.model.UserIdentity;
import com.memsys.memory.ConversationSummaryService;
import com.memsys.memory.MemoryExtractor;
import com.memsys.memory.MemoryManager;
import com.memsys.memory.MemoryScopeContext;
import com.memsys.memory.MemoryTraceInsightService;
import com.memsys.memory.MemoryWriteService;
import com.memsys.memory.ProactiveReminderService;
import com.memsys.memory.WeeklyReviewService;
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
import java.util.concurrent.LinkedBlockingQueue;

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
    private final ConversationSummaryService conversationSummaryService;
    private final ProactiveReminderService proactiveReminderService;
    private final WeeklyReviewService weeklyReviewService;
    private final MemoryTraceInsightService memoryTraceInsightService;
    private final UserIdentityService userIdentityService;

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
    private String activeScope = MemoryScopeContext.DEFAULT_SCOPE;
    private String cliUnifiedId = "user_default";
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
            ScheduledTaskService scheduledTaskService,
            ConversationSummaryService conversationSummaryService,
            ProactiveReminderService proactiveReminderService,
            WeeklyReviewService weeklyReviewService,
            MemoryTraceInsightService memoryTraceInsightService,
            UserIdentityService userIdentityService
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
        this.conversationSummaryService = conversationSummaryService;
        this.proactiveReminderService = proactiveReminderService;
        this.weeklyReviewService = weeklyReviewService;
        this.memoryTraceInsightService = memoryTraceInsightService;
        this.userIdentityService = userIdentityService;
    }

    @Override
    public void run(String... args) {
        startupNotes.clear();
        startupWarnings.clear();
        this.temporaryMode = false;
        this.cliUnifiedId = userIdentityService.resolveUnifiedId("cli", "default");
        this.activeScope = MemoryScopeContext.personalScope(cliUnifiedId);
        for (String arg : args) {
            if ("--temporary".equals(arg) || "temp".equals(arg)) {
                this.temporaryMode = true;
                break;
            }
        }

        if (temporaryMode) {
            startupWarnings.add("temporary 模式：不读取/写入长期记忆。");
        } else {
            try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(activeScope)) {
                loadCliUiSettingsFromMetadata();
            }
            startupNotes.add("当前作用域: " + activeScope);
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
                    try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(activeScope)) {
                        if (!handleCommand(input, lineReader)) {
                            break;
                        }
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
            case "/memory-debug" -> {
                if (parts.length == 1) {
                    showMemoryDebug();
                } else {
                    try {
                        int limit = Integer.parseInt(parts[1]);
                        if (limit <= 0) {
                            printSystem("用法: /memory-debug 或 /memory-debug <N>（N>0）");
                            break;
                        }
                        showMemoryDebugHistory(limit);
                    } catch (NumberFormatException e) {
                        printSystem("用法: /memory-debug 或 /memory-debug <N>（N>0）");
                    }
                }
            }
            case "/memory-timeline" -> showMemoryTimeline();
            case "/memory-review" -> showMemoryReview();
            case "/memory-report" -> showMemoryReport();
            case "/memory-scenes" -> showMemoryScenes();
            case "/memory-insights" -> {
                int limit = 50;
                if (parts.length >= 2) {
                    try {
                        limit = Integer.parseInt(parts[1]);
                        if (limit <= 0) {
                            printSystem("用法: /memory-insights [limit>0]");
                            break;
                        }
                    } catch (NumberFormatException e) {
                        printSystem("用法: /memory-insights [limit>0]");
                        break;
                    }
                }
                showMemoryInsights(limit);
            }
            case "/memory-governance" -> showMemoryGovernance();
            case "/proactive-reminders" -> showProactiveReminders();
            case "/identity" -> showIdentityMappings();
            case "/scope" -> handleScopeCommand(command);
            case "/team" -> {
                if (parts.length < 2) {
                    printSystem("用法: /team <teamId>");
                } else {
                    switchToTeamScope(parts[1]);
                }
            }
            case "/weekly-report" -> showWeeklyReport();
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
            if (task.getRecurrenceType() != null
                    && !task.getRecurrenceType().isBlank()
                    && !ScheduledTask.RECURRENCE_NONE.equalsIgnoreCase(task.getRecurrenceType())) {
                int interval = task.getRecurrenceInterval() == null || task.getRecurrenceInterval() <= 0
                        ? 1
                        : task.getRecurrenceInterval();
                sb.append(" | recur=").append(task.getRecurrenceType()).append("/").append(interval);
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

        boolean useSavedMemories = globalControls == null
                || parseBoolean(globalControls.get("use_saved_memories"), true);
        boolean useChatHistory = globalControls == null
                || parseBoolean(globalControls.get("use_chat_history"), true);

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

    private void showMemoryDebugHistory(int limit) {
        List<MemoryEvidenceTrace> traces = conversationCli.getRecentEvidenceTraces(limit);
        if (traces.isEmpty()) {
            printSystem("暂无记忆反思记录。请先发送一条消息，再使用 /memory-debug 查看。");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║      🧪 Memory Debug History (最近追踪)   ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");
        sb.append(String.format("▸ 最近 %d 条 evidence trace（最新在前）\n\n", traces.size()));

        for (int i = traces.size() - 1, rank = 1; i >= 0; i--, rank++) {
            MemoryEvidenceTrace trace = traces.get(i);
            String timestamp = trace.timestamp() == null ? "" : trace.timestamp().toString();
            if (timestamp.isBlank()) {
                timestamp = "(unknown)";
            }
            String userMessage = truncateForDisplay(trace.userMessage(), 80);
            String needsMemoryLabel = traceNeedsMemoryLabel(trace);
            String reason = traceReason(trace);
            String purpose = traceMemoryPurpose(trace);
            String confidence = traceConfidence(trace);
            String retrievalHint = traceRetrievalHint(trace);
            String evidenceTypes = traceEvidenceTypes(trace);
            String evidencePurposes = traceEvidencePurposes(trace);

            List<String> retrievedInsights = trace.retrievedInsights();
            List<String> retrievedExamples = trace.retrievedExamples();
            List<String> loadedSkills = trace.loadedSkills();
            List<String> retrievedTasks = trace.retrievedTasks();
            List<String> usedInsights = trace.usedInsights();
            List<String> usedExamples = trace.usedExamples();
            List<String> usedSkills = trace.usedSkills();
            List<String> usedTasks = trace.usedTasks();
            String usedSummary = trace.usedEvidenceSummary() == null ? "" : trace.usedEvidenceSummary().trim();

            sb.append(String.format("── #%d %s ──\n", rank, timestamp));
            sb.append(String.format("  需要记忆: %s\n", needsMemoryLabel));
            sb.append(String.format("  记忆目的: %s\n", purpose));
            sb.append(String.format("  判断理由: %s\n", reason));
            sb.append(String.format("  置信度: %s\n", confidence));
            if (!retrievalHint.isBlank()) {
                sb.append(String.format("  检索提示: %s\n", truncateForDisplay(retrievalHint, 120)));
            }
            if (!evidenceTypes.isBlank()) {
                sb.append(String.format("  证据类型: %s\n", evidenceTypes));
            }
            if (!evidencePurposes.isBlank()) {
                sb.append(String.format("  证据用途: %s\n", evidencePurposes));
            }
            sb.append(String.format("  用户消息: %s\n", userMessage.isBlank() ? "(empty)" : userMessage));
            sb.append(String.format("  检索: Insights %d | Examples %d | Skills %d | Tasks %d\n",
                    retrievedInsights.size(), retrievedExamples.size(), loadedSkills.size(), retrievedTasks.size()));
            sb.append(String.format("  使用: Insights %d | Examples %d | Skills %d | Tasks %d\n",
                    usedInsights.size(), usedExamples.size(), usedSkills.size(), usedTasks.size()));
            sb.append(String.format("  覆盖率: Insights %s | Examples %s | Skills %s | Tasks %s\n",
                    usageCoverage(usedInsights.size(), retrievedInsights.size()),
                    usageCoverage(usedExamples.size(), retrievedExamples.size()),
                    usageCoverage(usedSkills.size(), loadedSkills.size()),
                    usageCoverage(usedTasks.size(), retrievedTasks.size())));
            appendCoverageDiagnosis(sb, "Insights", usedInsights.size(), retrievedInsights.size());
            appendCoverageDiagnosis(sb, "Examples", usedExamples.size(), retrievedExamples.size());
            appendCoverageDiagnosis(sb, "Skills", usedSkills.size(), loadedSkills.size());
            appendCoverageDiagnosis(sb, "Tasks", usedTasks.size(), retrievedTasks.size());
            appendUnusedEvidencePreview(sb, "Insights", retrievedInsights, usedInsights);
            appendUnusedEvidencePreview(sb, "Examples", retrievedExamples, usedExamples);
            appendUnusedEvidencePreview(sb, "Skills", loadedSkills, usedSkills);
            appendUnusedEvidencePreview(sb, "Tasks", retrievedTasks, usedTasks);
            if (!usedSummary.isBlank()) {
                sb.append(String.format("  摘要: %s\n", truncateForDisplay(usedSummary, 120)));
            }
            sb.append("\n");
        }

        printSystem(sb.toString());
    }

    // ========== 记忆系统展示命令（Phase 8） ==========

    private void showMemoryTimeline() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║       📋 Memory System Timeline          ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        // 1. 会话摘要时间线
        List<Map<String, Object>> summaries = conversationSummaryService.getRecentSummaries(5);
        sb.append("── 会话摘要 (Session Summaries) ──\n");
        if (summaries.isEmpty()) {
            sb.append("  (暂无摘要记录，对话达到 20 轮后自动生成)\n");
        } else {
            for (int i = 0; i < summaries.size(); i++) {
                Map<String, Object> s = summaries.get(i);
                String timeRange = String.valueOf(s.getOrDefault("time_range", "unknown"));
                String summary = String.valueOf(s.getOrDefault("summary", ""));
                Object topicsObj = s.get("key_topics");
                String topics = formatTopics(topicsObj);
                int fromTurn = toInt(s.get("from_turn"));
                int toTurn = toInt(s.get("to_turn"));

                sb.append(String.format("  %s [轮次 %d-%d] %s\n", timelineMarker(i, summaries.size()), fromTurn, toTurn, timeRange));
                if (summary.length() > 120) {
                    summary = summary.substring(0, 120) + "...";
                }
                sb.append("    ").append(summary).append("\n");
                if (!topics.isEmpty()) {
                    sb.append("    话题: ").append(topics).append("\n");
                }
                sb.append("\n");
            }
        }

        // 2. 最近的 Memory Evidence Trace
        sb.append("── 最近记忆反思 (Last Evidence Trace) ──\n");
        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        if (trace == null) {
            sb.append("  (暂无反思记录)\n");
        } else {
            sb.append(String.format("  需要记忆: %s | 理由: %s\n",
                    traceNeedsMemoryLabel(trace),
                    traceReason(trace)));
            sb.append(String.format("  检索: Insights %d | Examples %d | Skills %d | Tasks %d\n",
                    trace.retrievedInsights().size(),
                    trace.retrievedExamples().size(),
                    trace.loadedSkills().size(),
                    trace.retrievedTasks().size()));
            sb.append(String.format("  使用: Insights %d | Examples %d | Skills %d | Tasks %d\n",
                    trace.usedInsights().size(),
                    trace.usedExamples().size(),
                    trace.usedSkills().size(),
                    trace.usedTasks().size()));
        }

        // 3. 当前会话状态
        sb.append("\n── 当前会话 ──\n");
        sb.append(String.format("  累计轮次: %d\n", conversationSummaryService.getCurrentTurnCount()));

        printSystem(sb.toString());
    }

    private void showMemoryReview() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║       🧭 Memory Review (记忆复盘)         ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        // 1) 最近一轮反思与证据
        sb.append("▸ 最近一轮反思\n");
        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        if (trace == null) {
            sb.append("  (暂无反思记录，先进行一轮对话后再查看)\n");
        } else {
            String needsMemoryLabel = traceNeedsMemoryLabel(trace);
            String needsMemoryText = describeNeedsMemoryLabel(needsMemoryLabel);
            sb.append(String.format("  记忆需求: %s\n", needsMemoryText));
            sb.append(String.format("  判断理由: %s\n", traceReason(trace)));
            sb.append(String.format("  证据检索: Insights %d | Examples %d | Skills %d | Tasks %d\n",
                    trace.retrievedInsights().size(),
                    trace.retrievedExamples().size(),
                    trace.loadedSkills().size(),
                    trace.retrievedTasks().size()));
            sb.append(String.format("  证据使用: Insights %d | Examples %d | Skills %d | Tasks %d\n",
                    trace.usedInsights().size(),
                    trace.usedExamples().size(),
                    trace.usedSkills().size(),
                    trace.usedTasks().size()));
            sb.append(String.format("  证据摘要: %s\n", trace.usedEvidenceSummary()));
        }
        sb.append("\n");

        // 2) 最近摘要与会话压缩状态
        sb.append("▸ 会话摘要\n");
        List<Map<String, Object>> summaries = conversationSummaryService.getRecentSummaries(3);
        if (summaries.isEmpty()) {
            sb.append("  (暂无摘要；对话达到阈值或发生主题切换后自动生成)\n");
        } else {
            Map<String, Object> latest = summaries.get(summaries.size() - 1);
            int fromTurn = toInt(latest.get("from_turn"));
            int toTurn = toInt(latest.get("to_turn"));
            String trigger = String.valueOf(latest.getOrDefault("trigger", "turn_threshold"));
            String triggerLabel = "topic_shift".equals(trigger) ? "主题切换" : "轮次阈值";
            String summaryText = String.valueOf(latest.getOrDefault("summary", ""));
            sb.append(String.format("  最新摘要: 轮次 %d-%d（触发: %s）\n", fromTurn, toTurn, triggerLabel));
            sb.append("  摘要内容: ").append(truncateForDisplay(summaryText, 120)).append("\n");
            sb.append(String.format("  摘要总数: %d | Prompt 压缩: 已激活\n", summaries.size()));
        }
        sb.append("\n");

        // 3) 治理状态概览
        sb.append("▸ 记忆治理\n");
        Map<String, Memory> memories = memoryManager.listAllMemories();
        long activeCount = memories.values().stream()
                .filter(m -> m.getStatus() == Memory.MemoryStatus.ACTIVE || m.getStatus() == null)
                .count();
        long pendingCount = memories.values().stream()
                .filter(m -> m.getStatus() == Memory.MemoryStatus.PENDING)
                .count();
        long conflictCount = memories.values().stream()
                .filter(m -> m.getStatus() == Memory.MemoryStatus.CONFLICT)
                .count();
        List<Map<String, Object>> pendingQueue = storage.readPendingExplicitMemories();
        sb.append(String.format("  ACTIVE: %d | PENDING: %d | CONFLICT: %d\n", activeCount, pendingCount, conflictCount));
        sb.append(String.format("  待处理队列: %d 条（/memory-governance 查看详情）\n", pendingQueue.size()));
        sb.append("\n");

        // 4) 近期任务状态
        sb.append("▸ 近期任务\n");
        List<ScheduledTask> tasks = scheduledTaskService.listTasks(5);
        if (tasks.isEmpty()) {
            sb.append("  (暂无任务)\n");
        } else {
            long todoCount = tasks.stream()
                    .filter(task -> ScheduledTask.STATUS_PENDING.equalsIgnoreCase(task.getStatus()))
                    .count();
            long triggeredCount = tasks.stream()
                    .filter(task -> ScheduledTask.STATUS_TRIGGERED.equalsIgnoreCase(task.getStatus()))
                    .count();
            long cancelledCount = tasks.stream()
                    .filter(task -> ScheduledTask.STATUS_CANCELLED.equalsIgnoreCase(task.getStatus()))
                    .count();
            sb.append(String.format("  最近 %d 条任务：待执行 %d | 已触发 %d | 已取消 %d\n",
                    tasks.size(), todoCount, triggeredCount, cancelledCount));
            ScheduledTask latestTask = tasks.get(tasks.size() - 1);
            String latestTitle = latestTask.getTitle() == null ? "(untitled)" : latestTask.getTitle();
            String latestDue = latestTask.getDueAt() == null ? "unknown" : latestTask.getDueAt().toString();
            sb.append(String.format("  最近任务: %s（%s）\n", truncateForDisplay(latestTitle, 50), latestDue));
        }
        sb.append("\n");

        sb.append(String.format("▸ 当前会话轮次: %d\n", conversationSummaryService.getCurrentTurnCount()));
        sb.append("▸ 建议：结合 /memory-debug、/memory-scenes 进行答辩演示");

        printSystem(sb.toString());
    }

    private void showMemoryReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║       📊 Memory System Report            ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        // L1 短期记忆
        sb.append("▸ L1 短期记忆 (Short-term)\n");
        sb.append(String.format("  当前会话轮次: %d\n", conversationSummaryService.getCurrentTurnCount()));
        List<Map<String, Object>> recentMsgs = storage.getRecentMessages(999);
        sb.append(String.format("  recent_user_messages: %d 条\n\n", recentMsgs.size()));

        // L2 元数据
        sb.append("▸ L2 元数据 (Metadata)\n");
        Map<String, Object> metadata = storage.readMetadata();
        @SuppressWarnings("unchecked")
        Map<String, Object> globalControls = (Map<String, Object>) metadata.get("global_controls");
        boolean useMem = globalControls == null
                || parseBoolean(globalControls.get("use_saved_memories"), true);
        boolean useHist = globalControls == null
                || parseBoolean(globalControls.get("use_chat_history"), true);
        sb.append(String.format("  use_saved_memories: %s | use_chat_history: %s\n\n", useMem ? "ON" : "OFF", useHist ? "ON" : "OFF"));

        // L3 用户洞察
        sb.append("▸ L3 用户洞察 (User Insights)\n");
        Map<String, Memory> memories = memoryManager.listAllMemories();
        sb.append(String.format("  记忆槽位: %d 个\n", memories.size()));
        String narrative = storage.readUserInsightsNarrative();
        boolean hasNarrative = narrative != null && !narrative.isBlank() && !narrative.contains("还没有形成稳定");
        sb.append(String.format("  用户画像: %s\n", hasNarrative ? "已形成" : "尚未形成"));
        // 治理摘要
        List<Map<String, Object>> pendingMems = storage.readPendingExplicitMemories();
        long conflictMems = pendingMems.stream()
                .filter(r -> "CONFLICT".equals(r.get("status")))
                .count();
        sb.append(String.format("  治理: 待处理 %d 条 (冲突 %d 条) — /memory-governance 查看详情\n",
                pendingMems.size(), conflictMems));

        // 主动提醒摘要
        List<Map<String, Object>> proactiveReminders = proactiveReminderService.getRecentReminders(0);
        LocalDateTime lastProactiveTime = proactiveReminderService.getLastReminderTime();
        sb.append(String.format("  主动提醒: 累计 %d 条 (上次: %s) — /proactive-reminders 查看详情\n\n",
                proactiveReminders.size(),
                lastProactiveTime != null
                        ? lastProactiveTime.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                        : "暂无"));

        // L4a Skill
        sb.append("▸ L4a 技能 (Skills)\n");
        List<SkillFile> skills = skillService.listSkills();
        sb.append(String.format("  可用 Skill: %d 个\n", skills.size()));
        if (!skills.isEmpty()) {
            for (SkillFile sf : skills) {
                sb.append("    - ").append(sf.name()).append("\n");
            }
        }
        sb.append("\n");

        // L4b RAG / Example
        sb.append("▸ L4b 语义检索 (RAG/Example)\n");
        sb.append(String.format("  RAG 启用: %s\n", ragEnabled ? "是" : "否"));
        if (ragEnabled) {
            try {
                Map<String, Object> stats = ragService.getStatistics();
                sb.append(String.format("  索引文档: %s 个 | 向量维度: %s\n",
                        stats.getOrDefault("total_documents", "?"),
                        stats.getOrDefault("vector_dimension", "?")));
            } catch (Exception e) {
                sb.append("  (无法获取 RAG 统计)\n");
            }
        }
        sb.append("\n");

        // 会话摘要
        sb.append("▸ 会话摘要 (Session Summaries)\n");
        List<Map<String, Object>> summaries = conversationSummaryService.getRecentSummaries(0);
        sb.append(String.format("  历史摘要: %d 条\n", summaries.size()));
        sb.append(String.format("  Prompt 压缩: %s\n\n", summaries.isEmpty() ? "未激活" : "已激活（摘要替代原始消息）"));

        // Memory Reflection
        sb.append("▸ 记忆反思 (Memory Reflection)\n");
        MemoryEvidenceTrace trace = conversationCli.getLastEvidenceTrace();
        sb.append(String.format("  状态: %s\n", trace != null ? "已运行" : "尚未运行"));
        if (trace != null) {
            sb.append(String.format("  最近判断: %s\n", describeNeedsMemoryLabel(traceNeedsMemoryLabel(trace))));
        }

        printSystem(sb.toString());
    }

    /**
     * /memory-governance — 记忆治理状态展示。
     * Phase 9 #1：展示记忆的治理元数据（状态分布、冲突待处理、验证信息）。
     */
    private void showMemoryGovernance() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║       🛡️ Memory Governance (记忆治理)     ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        // 统计各状态的记忆数量
        Map<String, Memory> allMemories = memoryManager.listAllMemories();
        int totalSlots = allMemories.size();
        int activeCount = 0, pendingCount = 0, conflictCount = 0, archivedCount = 0, noStatusCount = 0;
        int verifiedCount = 0;

        for (Map.Entry<String, Memory> entry : allMemories.entrySet()) {
            Memory m = entry.getValue();
            if (m.getStatus() == null) {
                noStatusCount++;
            } else {
                switch (m.getStatus()) {
                    case ACTIVE -> activeCount++;
                    case PENDING -> pendingCount++;
                    case CONFLICT -> conflictCount++;
                    case ARCHIVED -> archivedCount++;
                }
            }
            if (m.getVerifiedAt() != null) {
                verifiedCount++;
            }
        }

        sb.append("▸ 记忆状态分布\n");
        sb.append(String.format("  总槽位: %d\n", totalSlots));
        sb.append(String.format("  ✅ ACTIVE: %d | ⏳ PENDING: %d | ⚠️ CONFLICT: %d | 📦 ARCHIVED: %d\n",
                activeCount, pendingCount, conflictCount, archivedCount));
        if (noStatusCount > 0) {
            sb.append(String.format("  (无状态/旧数据: %d — 视为 ACTIVE)\n", noStatusCount));
        }
        sb.append(String.format("  已验证: %d / %d\n\n", verifiedCount, totalSlots));

        // 展示待处理的冲突记忆
        List<Map<String, Object>> pendingMemories = storage.readPendingExplicitMemories();
        sb.append("▸ 待处理队列 (pending_explicit_memories.jsonl)\n");
        if (pendingMemories.isEmpty()) {
            sb.append("  (无待处理记忆)\n\n");
        } else {
            sb.append(String.format("  共 %d 条待处理\n", pendingMemories.size()));
            int shown = 0;
            for (Map<String, Object> record : pendingMemories) {
                if (shown >= 5) {
                    sb.append(String.format("  ... 还有 %d 条\n", pendingMemories.size() - shown));
                    break;
                }
                String slot = String.valueOf(record.getOrDefault("slot_name", "?"));
                String status = String.valueOf(record.getOrDefault("status", "?"));
                String newContent = truncateForDisplay(
                        String.valueOf(record.getOrDefault("new_content", "")), 60);
                String detectedAt = String.valueOf(record.getOrDefault("detected_at", "?"));
                sb.append(String.format("  [%s] %s → \"%s\" (at %s)\n",
                        status, slot, newContent, detectedAt));
                shown++;
            }
            sb.append("\n");
        }

        // 展示有验证信息的记忆样本
        sb.append("▸ 最近验证记录\n");
        List<Map.Entry<String, Memory>> verified = allMemories.entrySet().stream()
                .filter(e -> e.getValue().getVerifiedAt() != null)
                .sorted((a, b) -> b.getValue().getVerifiedAt().compareTo(a.getValue().getVerifiedAt()))
                .limit(5)
                .toList();
        if (verified.isEmpty()) {
            sb.append("  (暂无验证记录 — 旧记忆无此字段)\n");
        } else {
            for (Map.Entry<String, Memory> entry : verified) {
                Memory m = entry.getValue();
                sb.append(String.format("  %s — %s [%s] at %s\n",
                        entry.getKey(),
                        m.getVerifiedSource() != null ? m.getVerifiedSource() : "?",
                        m.getSource() != null ? m.getSource() : "?",
                        m.getVerifiedAt()));
            }
        }

        printSystem(sb.toString());
    }

    private void showProactiveReminders() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║       💡 Proactive Reminders (主动提醒)   ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        List<Map<String, Object>> reminders = proactiveReminderService.getRecentReminders(10);

        if (reminders.isEmpty()) {
            sb.append("  （暂无主动提醒记录）\n\n");
            sb.append("  主动提醒会在系统运行期间，基于你的用户画像和对话历史，\n");
            sb.append("  每 4 小时自动检查是否有值得提醒的内容。\n");
        } else {
            sb.append(String.format("▸ 最近 %d 条提醒记录\n\n", reminders.size()));
            for (int i = reminders.size() - 1; i >= 0; i--) {
                Map<String, Object> r = reminders.get(i);
                String time = String.valueOf(r.getOrDefault("generated_at", "unknown"));
                String type = String.valueOf(r.getOrDefault("reminder_type", "?"));
                String text = String.valueOf(r.getOrDefault("reminder_text", ""));
                String action = String.valueOf(r.getOrDefault("suggested_action", ""));
                Object basedOn = r.get("based_on_memories");
                String basedOnStr = basedOn instanceof List<?> list
                        ? list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "))
                        : "";

                String typeIcon = switch (type) {
                    case "review" -> "📖";
                    case "suggestion" -> "💡";
                    case "follow_up" -> "📌";
                    case "insight" -> "🔍";
                    default -> "📬";
                };

                sb.append(String.format("  %s [%s] %s\n", typeIcon, type, time));
                sb.append(String.format("    %s\n", text));
                if (!action.isBlank()) {
                    sb.append(String.format("    → 建议: %s\n", action));
                }
                if (!basedOnStr.isBlank()) {
                    sb.append(String.format("    基于: %s\n", basedOnStr));
                }
                sb.append("\n");
            }
        }

        LocalDateTime lastTime = proactiveReminderService.getLastReminderTime();
        sb.append(String.format("▸ 上次提醒时间: %s\n",
                lastTime != null ? lastTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : "（本会话尚未生成）"));

        printSystem(sb.toString());
    }

    private void showIdentityMappings() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║     🔗 Identity Mappings (身份映射)       ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        List<UserIdentity> identities = userIdentityService.listAllIdentities();

        if (identities.isEmpty()) {
            sb.append("  （暂无身份映射记录）\n\n");
            sb.append("  身份映射会在以下情况自动创建：\n");
            sb.append("  - CLI 对话时自动绑定默认身份\n");
            sb.append("  - 飞书/Telegram 消息到达时自动绑定平台用户\n\n");
            sb.append("  手动合并身份：在对话中告诉系统你的跨平台 ID\n");
        } else {
            sb.append(String.format("▸ 已注册 %d 个统一身份\n\n", identities.size()));
            for (UserIdentity identity : identities) {
                sb.append(String.format("  📋 %s", identity.getUnifiedId()));
                if (identity.getDisplayName() != null && !identity.getDisplayName().isBlank()) {
                    sb.append(String.format(" (%s)", identity.getDisplayName()));
                }
                sb.append("\n");

                Map<String, String> bindings = identity.getPlatformBindings();
                if (bindings != null && !bindings.isEmpty()) {
                    for (Map.Entry<String, String> binding : bindings.entrySet()) {
                        String platformIcon = switch (binding.getKey()) {
                            case "cli" -> "💻";
                            case "feishu" -> "🐦";
                            case "telegram" -> "✈️";
                            default -> "🔌";
                        };
                        sb.append(String.format("    %s %s: %s\n",
                                platformIcon, binding.getKey(), binding.getValue()));
                    }
                } else {
                    sb.append("    (无平台绑定)\n");
                }

                if (identity.getCreatedAt() != null) {
                    sb.append(String.format("    创建于: %s\n",
                            identity.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
                }
                sb.append("\n");
            }
        }

        // 统计
        long totalBindings = identities.stream()
                .mapToLong(id -> id.getPlatformBindings() != null ? id.getPlatformBindings().size() : 0)
                .sum();
        sb.append(String.format("▸ 统计: %d 个统一身份, %d 个平台绑定\n",
                identities.size(), totalBindings));

        printSystem(sb.toString());
    }

    private void showWeeklyReport() {
        try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(activeScope)) {
            String report = weeklyReviewService.generateCurrentScopeReview();
            printSystem(report);
        } catch (Exception e) {
            log.warn("Generate weekly report failed", e);
            printSystem("生成周报失败，请稍后重试。");
        }
    }

    private void handleScopeCommand(String command) {
        String[] parts = command.trim().split("\\s+", 3);
        if (parts.length == 1) {
            printSystem("当前作用域: " + activeScope + "\n用法: /scope personal [unifiedId] | /scope team <teamId>");
            return;
        }
        String target = parts[1].toLowerCase(Locale.ROOT);
        if ("personal".equals(target)) {
            String unifiedId = parts.length >= 3 ? parts[2] : cliUnifiedId;
            activeScope = MemoryScopeContext.personalScope(unifiedId);
            printSystem("已切换到个人作用域: " + activeScope);
            return;
        }
        if ("team".equals(target)) {
            if (parts.length < 3 || parts[2].isBlank()) {
                printSystem("用法: /scope team <teamId>");
                return;
            }
            switchToTeamScope(parts[2]);
            return;
        }
        printSystem("未知作用域类型: " + target + "\n用法: /scope personal [unifiedId] | /scope team <teamId>");
    }

    private void switchToTeamScope(String teamId) {
        activeScope = MemoryScopeContext.teamScope(teamId);
        printSystem("已切换到团队作用域: " + activeScope);
    }

    private String truncateForDisplay(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    private void showMemoryScenes() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════╗\n");
        sb.append("║       🎬 Memory Scenes (场景视图)         ║\n");
        sb.append("╚══════════════════════════════════════════╝\n\n");

        // 读取所有摘要
        List<Map<String, Object>> summaries = conversationSummaryService.getRecentSummaries(0);
        if (summaries.isEmpty()) {
            sb.append("  (暂无会话摘要，无法生成场景视图)\n");
            sb.append("  提示：对话达到 20 轮或主题切换时会自动生成摘要\n");
            printSystem(sb.toString());
            return;
        }

        // 按话题聚类：收集所有出现过的话题 -> 关联的摘要索引
        Map<String, List<Integer>> topicToSummaryIndices = new LinkedHashMap<>();
        for (int i = 0; i < summaries.size(); i++) {
            Map<String, Object> s = summaries.get(i);
            Object topicsObj = s.get("key_topics");
            List<String> topics = extractTopicList(topicsObj);

            // 如果是主题切换触发的，也把 previous_topic / current_topic 作为话题
            String trigger = String.valueOf(s.getOrDefault("trigger", ""));
            if ("topic_shift".equals(trigger)) {
                String prevTopic = String.valueOf(s.getOrDefault("previous_topic", "")).trim();
                String currTopic = String.valueOf(s.getOrDefault("current_topic", "")).trim();
                if (!prevTopic.isEmpty()) topics.add(prevTopic);
                if (!currTopic.isEmpty()) topics.add(currTopic);
            }

            if (topics.isEmpty()) {
                topics = List.of("其他");
            }
            for (String topic : topics) {
                String normalizedTopic = topic.trim().toLowerCase();
                if (normalizedTopic.isEmpty()) continue;
                topicToSummaryIndices.computeIfAbsent(topic.trim(), k -> new ArrayList<>()).add(i);
            }
        }

        // 按话题出现次数降序排列
        List<Map.Entry<String, List<Integer>>> sortedTopics = new ArrayList<>(topicToSummaryIndices.entrySet());
        sortedTopics.sort((a, b) -> b.getValue().size() - a.getValue().size());

        sb.append(String.format("  共 %d 条摘要，归入 %d 个话题场景\n\n", summaries.size(), sortedTopics.size()));

        int sceneNum = 1;
        for (Map.Entry<String, List<Integer>> entry : sortedTopics) {
            String topic = entry.getKey();
            List<Integer> indices = entry.getValue();

            sb.append(String.format("── 场景 %d: %s (%d 条摘要) ──\n", sceneNum, topic, indices.size()));

            for (int idx : indices) {
                Map<String, Object> s = summaries.get(idx);
                String timeRange = String.valueOf(s.getOrDefault("time_range", "unknown"));
                String summary = String.valueOf(s.getOrDefault("summary", ""));
                String triggerType = String.valueOf(s.getOrDefault("trigger", "turn_threshold"));
                int fromTurn = toInt(s.get("from_turn"));
                int toTurn = toInt(s.get("to_turn"));

                String triggerLabel = "topic_shift".equals(triggerType) ? "主题切换" : "轮次阈值";

                sb.append(String.format("  [轮次 %d-%d] %s (触发: %s)\n", fromTurn, toTurn, timeRange, triggerLabel));
                if (summary.length() > 150) {
                    summary = summary.substring(0, 150) + "...";
                }
                sb.append("    ").append(summary).append("\n\n");
            }
            sceneNum++;
        }

        // 总览统计
        long topicShiftCount = summaries.stream()
                .filter(s -> "topic_shift".equals(String.valueOf(s.getOrDefault("trigger", ""))))
                .count();
        long turnThresholdCount = summaries.size() - topicShiftCount;
        sb.append("── 统计 ──\n");
        sb.append(String.format("  轮次阈值触发: %d 条 | 主题切换触发: %d 条\n", turnThresholdCount, topicShiftCount));
        sb.append(String.format("  当前累计轮次: %d\n", conversationSummaryService.getCurrentTurnCount()));

        printSystem(sb.toString());
    }

    private void showMemoryInsights(int limit) {
        try {
            MemoryTraceInsightService.InsightReport report = memoryTraceInsightService.analyzeRecentTraces(limit);
            StringBuilder sb = new StringBuilder();
            sb.append("╔══════════════════════════════════════════╗\n");
            sb.append("║      🔬 Memory Insights (证据洞察)        ║\n");
            sb.append("╚══════════════════════════════════════════╝\n\n");

            sb.append(String.format("▸ 样本窗口: 最近 %d 条，实际 %d 条\n",
                    report.requestedLimit(), report.sampleSize()));
            sb.append(String.format("▸ 反思命中: needs_memory %.1f%% | memory_loaded %.1f%% | unknown %.1f%%\n\n",
                    report.needsMemoryRate() * 100.0d,
                    report.memoryLoadedRate() * 100.0d,
                    report.unknownNeedsMemoryRate() * 100.0d));

            sb.append("▸ 证据使用率（used / retrieved）\n");
            appendEvidenceRate(sb, "insights", report.insightStat());
            appendEvidenceRate(sb, "examples", report.exampleStat());
            appendEvidenceRate(sb, "skills", report.skillStat());
            appendEvidenceRate(sb, "tasks", report.taskStat());
            sb.append("\n");

            appendTrendSection(sb, report.trendSummary());
            sb.append("\n");

            appendTopList(sb, "高频 skill", report.topUsedSkills());
            appendTopList(sb, "高频用途", report.topPurposes());
            appendTopList(sb, "高频理由", report.topReasons());
            sb.append("\n");

            appendPurposeInsights(sb, report.topPurposeInsights());
            sb.append("\n");

            sb.append("▸ 优化建议\n");
            for (String suggestion : report.suggestions()) {
                sb.append("  - ").append(suggestion).append("\n");
            }
            sb.append("\n");
            sb.append("提示: 可结合 /memory-debug 与 /memory-review 定位单轮证据链路。");

            printSystem(sb.toString());
        } catch (Exception e) {
            log.warn("Build memory insights report failed", e);
            printSystem("生成 memory insights 失败，请稍后重试。");
        }
    }

    private void appendEvidenceRate(StringBuilder sb,
                                    String label,
                                    MemoryTraceInsightService.EvidenceStat stat) {
        if (stat == null) {
            sb.append(String.format("  - %s: 0/0 (0.0%%)\n", label));
            return;
        }
        sb.append(String.format("  - %s: %d/%d (%.1f%%)\n",
                label, stat.used(), stat.retrieved(), stat.usageRate() * 100.0d));
    }

    private void appendTrendSection(StringBuilder sb, MemoryTraceInsightService.TrendSummary trendSummary) {
        sb.append("▸ 趋势对比（前半窗口 -> 后半窗口）\n");
        if (trendSummary == null || !trendSummary.available()) {
            sb.append("  - 样本不足（至少 6 条）\n");
            return;
        }

        sb.append(String.format("  - 窗口规模: 前 %d 条 | 后 %d 条\n",
                trendSummary.previousWindowSize(), trendSummary.recentWindowSize()));
        appendTrendLine(sb, "memory_loaded", trendSummary.memoryLoadedTrend());
        appendTrendLine(sb, "insights_usage", trendSummary.insightUsageTrend());
        appendTrendLine(sb, "examples_usage", trendSummary.exampleUsageTrend());
        appendTrendLine(sb, "skills_usage", trendSummary.skillUsageTrend());
        appendTrendLine(sb, "tasks_usage", trendSummary.taskUsageTrend());
    }

    private void appendTrendLine(StringBuilder sb, String label, MemoryTraceInsightService.TrendStat trend) {
        if (trend == null) {
            sb.append(String.format("  - %s: n/a\n", label));
            return;
        }
        sb.append(String.format("  - %s: %.1f%% -> %.1f%% (%s)\n",
                label,
                trend.previousRate() * 100.0d,
                trend.recentRate() * 100.0d,
                formatTrendDelta(trend.delta())));
    }

    private String formatTrendDelta(double delta) {
        if (Math.abs(delta) < 1e-6) {
            return "0.0pp";
        }
        return String.format("%+.1fpp", delta * 100.0d);
    }

    private void appendTopList(StringBuilder sb, String title, List<String> values) {
        sb.append("▸ ").append(title).append("\n");
        if (values == null || values.isEmpty()) {
            sb.append("  - (暂无)\n");
            return;
        }
        for (String value : values) {
            sb.append("  - ").append(value).append("\n");
        }
    }

    private void appendPurposeInsights(StringBuilder sb, List<MemoryTraceInsightService.PurposeInsight> insights) {
        sb.append("▸ 用途诊断（按 evidence_purpose）\n");
        if (insights == null || insights.isEmpty()) {
            sb.append("  - (暂无)\n");
            return;
        }
        for (MemoryTraceInsightService.PurposeInsight insight : insights) {
            sb.append(String.format("  - %s (样本 %d): loaded %.1f%% | I %.1f%% | E %.1f%% | S %.1f%% | T %.1f%%\n",
                    insight.purpose(),
                    insight.sampleSize(),
                    insight.memoryLoadedRate() * 100.0d,
                    insight.insightUsageRate() * 100.0d,
                    insight.exampleUsageRate() * 100.0d,
                    insight.skillUsageRate() * 100.0d,
                    insight.taskUsageRate() * 100.0d));
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTopicList(Object topicsObj) {
        List<String> result = new ArrayList<>();
        if (topicsObj instanceof List<?> topicList) {
            for (Object item : topicList) {
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) result.add(s);
            }
        }
        return result;
    }

    private String timelineMarker(int index, int total) {
        if (index == 0) return "┌";
        if (index == total - 1) return "└";
        return "├";
    }

    @SuppressWarnings("unchecked")
    private String formatTopics(Object topicsObj) {
        if (topicsObj instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
        }
        return "";
    }

    private int toInt(Object obj) {
        if (obj instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(obj)); } catch (Exception e) { return 0; }
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

    private String usageCoverage(int used, int retrieved) {
        if (retrieved <= 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.1f%%", used * 100.0d / retrieved);
    }

    private void appendCoverageDiagnosis(StringBuilder sb, String label, int used, int retrieved) {
        if (retrieved <= 0) {
            return;
        }
        if (used <= 0) {
            sb.append(String.format("  诊断: %s 已检索但未使用（0/%d）\n", label, retrieved));
            return;
        }
        double ratio = used * 1.0d / retrieved;
        if (ratio < 0.5d) {
            sb.append(String.format("  诊断: %s 使用偏低（%d/%d）\n", label, used, retrieved));
        }
    }

    private void appendUnusedEvidencePreview(StringBuilder sb,
                                             String label,
                                             List<String> retrieved,
                                             List<String> used) {
        String preview = previewUnusedEvidence(retrieved, used, 2);
        if (preview.isBlank()) {
            return;
        }
        sb.append(String.format("  未使用%s: %s\n", label, preview));
    }

    private String traceNeedsMemoryLabel(MemoryEvidenceTrace trace) {
        if (trace == null || trace.reflection() == null) {
            return "unknown";
        }
        return trace.reflection().needs_memory() ? "是" : "否";
    }

    static String describeNeedsMemoryLabel(String needsMemoryLabel) {
        if ("是".equals(needsMemoryLabel)) {
            return "需要记忆";
        }
        if ("否".equals(needsMemoryLabel)) {
            return "不需要记忆";
        }
        return "未知";
    }

    static String needsMemoryLabelFromRaw(Object rawValue) {
        if (rawValue == null) {
            return "unknown";
        }
        if (rawValue instanceof Boolean bool) {
            return bool ? "是" : "否";
        }
        String text = String.valueOf(rawValue).trim();
        if (text.isBlank()) {
            return "unknown";
        }
        if ("true".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text)
                || "1".equals(text)
                || "yes".equalsIgnoreCase(text)
                || "y".equalsIgnoreCase(text)
                || "是".equals(text)) {
            return "是";
        }
        if ("false".equalsIgnoreCase(text)
                || "off".equalsIgnoreCase(text)
                || "0".equals(text)
                || "no".equalsIgnoreCase(text)
                || "n".equalsIgnoreCase(text)
                || "否".equals(text)) {
            return "否";
        }
        return "unknown";
    }

    private String traceReason(MemoryEvidenceTrace trace) {
        if (trace == null || trace.reflection() == null || trace.reflection().reason() == null || trace.reflection().reason().isBlank()) {
            return "reflection_missing";
        }
        return trace.reflection().reason();
    }

    private String traceMemoryPurpose(MemoryEvidenceTrace trace) {
        if (trace == null || trace.reflection() == null || trace.reflection().memory_purpose() == null) {
            return "(unknown)";
        }
        String purpose = trace.reflection().memory_purpose().trim();
        return purpose.isBlank() ? "(unknown)" : purpose;
    }

    private String traceConfidence(MemoryEvidenceTrace trace) {
        if (trace == null || trace.reflection() == null) {
            return "(unknown)";
        }
        return String.format(Locale.ROOT, "%.2f", trace.reflection().confidence());
    }

    private String traceRetrievalHint(MemoryEvidenceTrace trace) {
        if (trace == null || trace.reflection() == null || trace.reflection().retrieval_hint() == null) {
            return "";
        }
        return trace.reflection().retrieval_hint().trim();
    }

    static String traceEvidenceTypes(MemoryEvidenceTrace trace) {
        if (trace == null || trace.reflection() == null || trace.reflection().evidence_types() == null) {
            return "";
        }
        return trace.reflection().evidence_types().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    static String traceEvidencePurposes(MemoryEvidenceTrace trace) {
        if (trace == null || trace.reflection() == null || trace.reflection().evidence_purposes() == null) {
            return "";
        }
        return trace.reflection().evidence_purposes().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    static String previewUnusedEvidence(List<String> retrieved, List<String> used, int maxItems) {
        if (retrieved == null || retrieved.isEmpty() || maxItems <= 0) {
            return "";
        }
        Set<String> usedKeys = normalizeEvidenceItems(used).keySet();
        List<String> unused = normalizeEvidenceItems(retrieved).entrySet().stream()
                .filter(entry -> !usedKeys.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (unused.isEmpty()) {
            return "";
        }
        int limit = Math.min(maxItems, unused.size());
        String preview = String.join("; ", unused.subList(0, limit));
        if (unused.size() > limit) {
            preview = preview + String.format(" ... +%d", unused.size() - limit);
        }
        return preview;
    }

    private static LinkedHashMap<String, String> normalizeEvidenceItems(List<String> items) {
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return normalized;
        }
        items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .forEach(item -> normalized.putIfAbsent(canonicalEvidenceKey(item), item));
        return normalized;
    }

    private static String canonicalEvidenceKey(String item) {
        if (item == null) {
            return "";
        }
        return item.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private boolean parseBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text) || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text) || "0".equals(text)) {
            return false;
        }
        return defaultValue;
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
        try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(activeScope)) {
            words.addAll(skillService.listSkillNames());
            words.addAll(memoryManager.listAllMemories().keySet());
        }
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
                + " · scope " + activeScope
                + " · theme " + theme
                + " · skills " + countSkills()
                + " · memories " + countMemories()
                + " · cwd " + cwd
                + " · " + now;
        System.out.println(style("┈ " + status, ANSI_DIM));
        if (showFooter) {
            System.out.println(style("/help /scope /team /weekly-report /memory-insights /theme /footer /shortcuts /skills /memories /tasks /memory-review /exit", ANSI_DIM));
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
        final String scopeSnapshot = activeScope;
        final String senderSnapshot = currentCliSourceSenderId(scopeSnapshot);
        final LinkedBlockingQueue<ConversationProgressEvent> progressQueue = new LinkedBlockingQueue<>();
        final List<ConversationProgressEvent> progressEvents = new ArrayList<>();
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(scopeSnapshot)) {
                return temporaryMode
                        ? conversationCli.processUserMessageTemporary(
                        input, "cli", scopeSnapshot, senderSnapshot, progressQueue::offer)
                        : conversationCli.processUserMessage(
                        input, "cli", scopeSnapshot, senderSnapshot, progressQueue::offer);
            }
        });
        int frame = 0;
        long startedAt = System.nanoTime();
        int renderedProcessLines = 0;
        try {
            while (!future.isDone()) {
                ConversationProgressEvent event;
                while ((event = progressQueue.poll()) != null) {
                    progressEvents.add(event);
                    String processLine = "· " + (event.message() == null || event.message().isBlank()
                            ? "处理中..."
                            : event.message());
                    System.out.print("\r" + ANSI_CLEAR_LINE);
                    System.out.println(style(processLine, ANSI_DIM));
                    renderedProcessLines++;
                }

                long elapsedSeconds = Duration.ofNanos(System.nanoTime() - startedAt).toSeconds();
                int phraseIndex = (int) ((elapsedSeconds / SPINNER_PHRASE_ROTATE_SECONDS) % THINKING_PHRASES.length);
                String stageHint = progressEvents.isEmpty()
                        ? THINKING_PHRASES[phraseIndex]
                        : progressEvents.get(progressEvents.size() - 1).message();
                String spinnerText = THINKING_FRAMES[frame]
                        + " "
                        + stageHint
                        + " ("
                        + elapsedSeconds
                        + "s)";
                System.out.print("\r" + ANSI_CLEAR_LINE + style(spinnerText, ANSI_DIM));
                System.out.flush();
                frame = (frame + 1) % THINKING_FRAMES.length;
                Thread.sleep(90);
            }
            ConversationProgressEvent remain;
            while ((remain = progressQueue.poll()) != null) {
                progressEvents.add(remain);
                String processLine = "· " + (remain.message() == null || remain.message().isBlank()
                        ? "处理中..."
                        : remain.message());
                System.out.print("\r" + ANSI_CLEAR_LINE);
                System.out.println(style(processLine, ANSI_DIM));
                renderedProcessLines++;
            }
            System.out.print("\r" + ANSI_CLEAR_LINE);
            System.out.flush();
            clearRenderedProcessLines(renderedProcessLines);
            if (!progressEvents.isEmpty()) {
                long totalMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
                String collapsed = "过程已收起 · " + progressEvents.size() + " 步 · " + formatDurationSeconds(totalMs)
                        + "s · 可用 /memory-debug 查看证据详情";
                System.out.println(style(collapsed, ANSI_DIM));
            }
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

    private void clearRenderedProcessLines(int lines) {
        if (lines <= 0) {
            return;
        }
        for (int i = 0; i < lines; i++) {
            System.out.print("\u001B[1A\r" + ANSI_CLEAR_LINE);
        }
        System.out.print("\r" + ANSI_CLEAR_LINE);
        System.out.flush();
    }

    private String formatDurationSeconds(long durationMs) {
        double seconds = Math.max(0, durationMs) / 1000.0d;
        return String.format(Locale.ROOT, "%.1f", seconds);
    }

    private int countSkills() {
        try {
            try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(activeScope)) {
                return skillService.listSkillNames().size();
            }
        } catch (Exception e) {
            log.debug("Failed to count skills for status bar", e);
            return 0;
        }
    }

    private int countMemories() {
        try {
            try (MemoryScopeContext.Scope ignored = MemoryScopeContext.useScope(activeScope)) {
                return memoryManager.listAllMemories().size();
            }
        } catch (Exception e) {
            log.debug("Failed to count memories for status bar", e);
            return 0;
        }
    }

    private String currentCliSourceSenderId(String scope) {
        if (scope == null || scope.isBlank()) {
            return cliUnifiedId;
        }
        int idx = scope.indexOf(':');
        if (idx < 0 || idx >= scope.length() - 1) {
            return cliUnifiedId;
        }
        String kind = scope.substring(0, idx);
        String value = scope.substring(idx + 1);
        if ("team".equalsIgnoreCase(kind)) {
            return "team:" + value;
        }
        return value;
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
        commands.put("/memory-debug", "展示最近一轮（或最近 N 轮）记忆反思与证据使用");
        commands.put("/memory-timeline", "记忆系统时间线视图");
        commands.put("/memory-review", "一页式记忆复盘（反思/摘要/治理/任务）");
        commands.put("/memory-report", "记忆系统综合状态报告");
        commands.put("/memory-scenes", "按话题/场景分组展示记忆摘要");
        commands.put("/memory-insights", "基于 trace 生成证据质量洞察与优化建议");
        commands.put("/memory-governance", "记忆治理状态（冲突、待审核、归档）");
        commands.put("/proactive-reminders", "查看主动提醒历史");
        commands.put("/identity", "查看统一身份映射");
        commands.put("/scope", "切换记忆作用域（个人/团队）");
        commands.put("/team", "快速切换到团队作用域");
        commands.put("/weekly-report", "生成本作用域每周复盘");
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
