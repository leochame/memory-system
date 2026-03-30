package com.memsys.prompt;

import com.memsys.memory.model.ReflectionResult;
import com.memsys.rag.RagService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SystemPromptBuilder {

    public String buildSystemPrompt(
            String agentGuide,
            Map<String, Object> userMetadata,
            Map<String, Object> assistantPreferences,
            ReflectionResult reflectionResult,
            List<Map<String, Object>> recentMessages,
            String userInsightsNarrative,
            List<String> availableSkillNames,
            boolean skillToolAvailable,
            boolean ragToolAvailable,
            boolean shellToolAvailable,
            boolean shellCommandToolAvailable,
            boolean pythonToolAvailable,
            boolean taskToolAvailable,
            List<String> retrievedTasks,
            String examplesContent,
            List<RagService.RelevantMemory> ragContext,
            String sessionSummariesText
    ) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# 系统提示词\n\n");

        // 1. Agent Navigation
        if (agentGuide != null && !agentGuide.isBlank()) {
            prompt.append("## 1. 会话启动导航地图\n");
            prompt.append(agentGuide.trim()).append("\n\n");
        }

        // 2. User Interaction Metadata
        if (userMetadata != null && !userMetadata.isEmpty()) {
            prompt.append("## 2. 用户交互元数据\n");
            userMetadata.forEach((key, value) ->
                prompt.append(String.format("- %s: %s\n", key, value))
            );
            prompt.append("\n");
        }

        // 3. Assistant Response Preferences
        if (assistantPreferences != null && !assistantPreferences.isEmpty()) {
            prompt.append("## 3. 助手回复偏好\n");
            assistantPreferences.forEach((key, value) ->
                prompt.append(String.format("- %s: %s\n", key, value))
            );
            prompt.append("\n");
        }

        // 3.5 Memory Reflection Decision
        if (reflectionResult != null) {
            prompt.append("## 3.5 记忆反思决策\n");
            prompt.append("- needs_memory: ").append(reflectionResult.needs_memory()).append("\n");
            if (reflectionResult.reason() != null && !reflectionResult.reason().isBlank()) {
                prompt.append("- reason: ").append(reflectionResult.reason().trim()).append("\n");
            }
            if (reflectionResult.evidence_purposes() != null && !reflectionResult.evidence_purposes().isEmpty()) {
                prompt.append("- evidence_purposes: ")
                        .append(String.join(", ", reflectionResult.evidence_purposes()))
                        .append("\n");
            }
            prompt.append("\n");
        }

        // 4. Recent Conversation Content — 当有会话摘要时用摘要替代原始消息（Phase 8 压缩）
        boolean hasSummaries = sessionSummariesText != null && !sessionSummariesText.isBlank();
        if (hasSummaries) {
            prompt.append("## 4. 历史对话摘要（压缩模式）\n");
            prompt.append("以下是之前对话的结构化摘要，已替代原始对话记录以节省上下文空间：\n\n");
            prompt.append(sessionSummariesText.trim()).append("\n\n");
        } else if (recentMessages != null && !recentMessages.isEmpty()) {
            prompt.append("## 4. 最近对话内容\n");
            for (Map<String, Object> msg : recentMessages) {
                String timestamp = (String) msg.get("timestamp");
                String message = (String) msg.get("message");
                prompt.append(String.format("- **%s**: %s\n", timestamp, message));
            }
            prompt.append("\n");
        }

        // 5. User Insights
        if (userInsightsNarrative != null && !userInsightsNarrative.isBlank()) {
            prompt.append("## 5. 用户画像正文（User Insights）\n");
            prompt.append(userInsightsNarrative.trim()).append("\n\n");
        }

        // 6. Skill Load Strategy
        if (availableSkillNames != null && !availableSkillNames.isEmpty()) {
            prompt.append("## 6. Skill 加载策略\n");
            prompt.append("当前不会自动注入全部 skill 正文。可用 skill 名称如下：\n");
            for (String skillName : availableSkillNames) {
                prompt.append("- ").append(skillName).append("\n");
            }
            prompt.append("\n");
            if (skillToolAvailable) {
                prompt.append("如需某个 skill 的正文，请调用 `load_skill(name)` 工具按名称加载，避免全量读取。\n\n");
            } else {
                prompt.append("当前未提供 skill 加载工具，直接基于现有上下文回答用户。\n\n");
            }
        } else if (!skillToolAvailable) {
            prompt.append("## 6. Skill 加载策略\n");
            prompt.append("当前没有可加载的 skill，直接基于现有上下文回答用户。\n\n");
        }

        // 7. Examples
        if (examplesContent != null && !examplesContent.isBlank()) {
            prompt.append("## 7. 相关案例（RAG Examples）\n");
            prompt.append("以下是与当前对话相关的历史 Problem/Solution 案例：\n\n");
            prompt.append(examplesContent);
            prompt.append("\n");
        }

        // 8. RAG Memory Context
        if (ragContext != null && !ragContext.isEmpty()) {
            prompt.append("## 8. 语义相关记忆（RAG Retrieved Context）\n");
            for (RagService.RelevantMemory mem : ragContext) {
                prompt.append(String.format("- **%s** (相关度: %.0f%%): %s\n",
                    mem.getSlotName(), mem.getScore() * 100, mem.getContent()));
            }
            prompt.append("\n");
        }

        // 9. Task Context
        if (retrievedTasks != null && !retrievedTasks.isEmpty()) {
            prompt.append("## 9. 相关任务上下文（Retrieved Tasks）\n");
            for (String task : retrievedTasks) {
                prompt.append("- ").append(task).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("---\n\n");
        prompt.append("重要说明：\n");
        prompt.append("- 以上信息是你的背景知识和记忆，用于理解用户和提供个性化回复\n");
        prompt.append("- 最近 10 轮完整对话通过 messages 列表单独传递\n");
        if (hasSummaries) {
            prompt.append("- 更早的对话已被压缩为摘要（见第 4 节），请基于摘要理解历史上下文\n");
        }
        if (ragToolAvailable) {
            prompt.append("- 如需更多相关记忆，可调用 `search_rag(query)` 工具做按需检索\n");
        }
        if (shellToolAvailable) {
            prompt.append("- 如需查询项目文件，可调用 `run_shell(command, cwd)` 进行只读 shell 检索\n");
        }
        if (shellCommandToolAvailable) {
            prompt.append("- 当用户明确要求执行命令或修改文件时，可调用 `run_shell_command(command, cwd)`\n");
        }
        if (pythonToolAvailable) {
            prompt.append("- 如需执行自动化脚本，可调用 `run_python_script(script, args_json)`（仅允许 scripts 目录下 .py 文件）\n");
            prompt.append("- 当用户明确要求执行脚本时，应优先调用该工具并基于脚本输出给出结果\n");
        }
        if (taskToolAvailable) {
            prompt.append("- 当用户明确提出提醒/日程需求时，可调用 `create_task(...)` 创建定时任务\n");
            prompt.append("- 若前端已提供明确标题/时间/命令，优先传 `title/due_at_iso/execute_command` 直接建任务\n");
        }
        prompt.append("- 当前系统支持定时任务与 IM 主动提醒；当用户提出提醒需求时，不要声称“无法主动提醒”\n");
        prompt.append("- 请根据 messages 列表中的实际对话内容进行回复，同时参考背景信息提供个性化回复\n");
        prompt.append("\n请基于以上背景信息和 messages 列表中的实际对话内容，与用户进行对话。\n");

        return prompt.toString();
    }

    public String buildTemporaryPrompt() {
        return """
            # 临时对话模式

            当前处于临时对话模式，不会读取或保存任何记忆。
            请正常与用户对话，但不要引用任何历史信息。
            """;
    }
}
