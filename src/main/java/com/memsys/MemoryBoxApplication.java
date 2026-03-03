package com.memsys;

import com.memsys.cli.ConversationCli;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Scanner;

@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableAsync
public class MemoryBoxApplication implements CommandLineRunner {

    private final ConversationCli conversationCli;

    public MemoryBoxApplication(ConversationCli conversationCli) {
        this.conversationCli = conversationCli;
    }

    public static void main(String[] args) {
        SpringApplication.run(MemoryBoxApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("记忆管理系统启动成功！");

        // 检查是否为临时模式
        boolean temporaryMode = false;
        for (String arg : args) {
            if ("--temporary".equals(arg) || "temp".equals(arg)) {
                temporaryMode = true;
                break;
            }
        }

        if (temporaryMode) {
            log.info("临时对话模式已启用");
            conversationCli.setTemporaryMode(true);
        }

        printWelcome();
        startCli();
    }

    private void printWelcome() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("欢迎使用记忆管理系统 (Memory Box)");
        System.out.println("=".repeat(60));
        System.out.println("可用命令:");
        System.out.println("  /help              - 显示帮助信息");
        System.out.println("  /memories          - 查看所有记忆");
        System.out.println("  /what-you-know     - 查看系统记住的关于你的信息");
        System.out.println("  /edit <槽位名>     - 编辑指定记忆");
        System.out.println("  /delete <槽位名>   - 删除指定记忆");
        System.out.println("  /cleanup           - 清理长期未访问的记忆");
        System.out.println("  /controls          - 查看全局控制设置");
        System.out.println("  /controls <项> <值> - 修改全局控制（如: /controls memories on）");
        System.out.println("  /memory-update     - 手动触发隐式记忆提取");
        System.out.println("  /exit     - 退出系统");
        System.out.println("=".repeat(60) + "\n");
    }

    private void startCli() {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("你: ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) {
                continue;
            }

            // 处理命令
            if (input.startsWith("/")) {
                if (!handleCommand(input)) {
                    break;
                }
                continue;
            }

            // 处理普通对话
            try {
                String response = conversationCli.processUserMessage(input);
                System.out.println("助手: " + response + "\n");
            } catch (Exception e) {
                log.error("处理消息时出错", e);
                System.out.println("抱歉，处理消息时出现错误: " + e.getMessage() + "\n");
            }
        }

        scanner.close();
        log.info("记忆管理系统已退出");
    }

    private boolean handleCommand(String command) {
        String[] parts = command.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();

        switch (cmd) {
            case "/help":
                printWelcome();
                break;

            case "/memories":
                conversationCli.showMemories();
                break;

            case "/what-you-know":
                conversationCli.showWhatYouKnow();
                break;

            case "/edit":
                if (parts.length < 2) {
                    System.out.println("用法: /edit <槽位名>\n");
                } else {
                    conversationCli.editMemory(parts[1]);
                }
                break;

            case "/delete":
                if (parts.length < 2) {
                    System.out.println("用法: /delete <槽位名>\n");
                } else {
                    conversationCli.deleteMemory(parts[1]);
                }
                break;

            case "/cleanup":
                conversationCli.cleanupOldMemories();
                break;

            case "/controls":
                if (parts.length == 1) {
                    conversationCli.showControls();
                } else if (parts.length == 3) {
                    conversationCli.setControl(parts[1], parts[2]);
                } else {
                    System.out.println("用法: /controls 或 /controls <项> <值>\n");
                    System.out.println("示例: /controls memories on\n");
                }
                break;

            case "/memory-update":
                conversationCli.triggerMemoryUpdate();
                break;

            case "/exit":
            case "/quit":
                System.out.println("再见！");
                return false;

            default:
                System.out.println("未知命令: " + cmd);
                System.out.println("输入 /help 查看可用命令\n");
        }

        return true;
    }
}
