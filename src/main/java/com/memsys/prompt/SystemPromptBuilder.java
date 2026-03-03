package com.memsys.prompt;

import com.memsys.memory.Memory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SystemPromptBuilder {

    /**
     * 构建完整的系统提示词，按六个区块组织
     */
    public String buildSystemPrompt(
            Map<String, Object> userMetadata,
            Map<String, Object> assistantPreferences,
            List<Map<String, Object>> recentMessages,
            Map<String, Memory> modelSetContext,
            Map<String, Memory> notableHighlights,
            Map<String, Memory> userInsights
    ) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("# 系统提示词\n\n");

        // 1. User Interaction Metadata
        if (userMetadata != null && !userMetadata.isEmpty()) {
            prompt.append("## 1. 用户交互元数据\n");
            userMetadata.forEach((key, value) ->
                prompt.append(String.format("- %s: %s\n", key, value))
            );
            prompt.append("\n");
        }

        // 2. Assistant Response Preferences
        if (assistantPreferences != null && !assistantPreferences.isEmpty()) {
            prompt.append("## 2. 助手回复偏好\n");
            assistantPreferences.forEach((key, value) ->
                prompt.append(String.format("- %s: %s\n", key, value))
            );
            Object confidence = assistantPreferences.get("confidence");
            if (confidence != null) {
                prompt.append(String.format("**置信度**: %s\n", confidence));
            }
            prompt.append("\n");
        }

        // 3. Recent Conversation Content（最近对话内容）
        // 包含第 11-40 轮的用户消息，作为"连续性日志"连接过去的讨论与当前对话
        // 注意：这里只显示用户的消息，不包括助手的回复
        if (recentMessages != null && !recentMessages.isEmpty()) {
            prompt.append("## 3. 最近对话内容（Recent Conversation Content）\n");
            prompt.append("以下是你最近与用户的对话记录，每次对话都有时间戳。这个\"连续性日志\"将过去的讨论与当前的对话连接起来。\n\n");
            
            for (Map<String, Object> msg : recentMessages) {
                String timestamp = (String) msg.get("timestamp");
                String message = (String) msg.get("message");
                prompt.append(String.format("- **%s**: %s\n", timestamp, message));
            }
            prompt.append("\n");
        }

        // 4. Model Set Context
        if (modelSetContext != null && !modelSetContext.isEmpty()) {
            prompt.append("## 4. 对话历史摘要（Model Set Context）\n");
            modelSetContext.forEach((slotName, memory) ->
                prompt.append(String.format("### %s\n%s\n\n", slotName, memory.getContent()))
            );
        }

        // 5. User Insights
        if (userInsights != null && !userInsights.isEmpty()) {
            prompt.append("## 5. 用户档案卡（User Insights）\n");
            userInsights.forEach((slotName, memory) -> {
                prompt.append(String.format("- **%s**: %s", slotName, memory.getContent()));
                if (memory.getConfidence() != null) {
                    prompt.append(String.format(" [置信度: %s]", memory.getConfidence()));
                }
                prompt.append("\n");
            });
            prompt.append("\n");
        }

        // 6. Notable Highlights
        if (notableHighlights != null && !notableHighlights.isEmpty()) {
            prompt.append("## 6. 显著话题（Notable Highlights）\n");
            notableHighlights.forEach((slotName, memory) ->
                prompt.append(String.format("- **%s**: %s\n", slotName, memory.getContent()))
            );
            prompt.append("\n");
        }

        prompt.append("---\n\n");
        prompt.append("重要说明：\n");
        prompt.append("- 以上信息是你的背景知识和记忆，用于理解用户和提供个性化回复\n");
        prompt.append("- 最近 10 轮完整对话（包括用户和助手的回复）会通过 messages 列表单独传递\n");
        prompt.append("- 第 3 部分的\"最近对话内容\"是第 11-40 轮的用户消息，作为连续性日志帮助你了解更早的讨论主题\n");
        prompt.append("- 请根据 messages 列表中的实际对话内容进行回复，同时参考背景信息提供个性化回复\n");
        prompt.append("\n请基于以上背景信息和 messages 列表中的实际对话内容，与用户进行对话。\n");

        return prompt.toString();
    }

    /**
     * 构建简化版系统提示词（临时对话模式）
     */
    public String buildTemporaryPrompt() {
        return """
            # 临时对话模式

            当前处于临时对话模式，不会读取或保存任何记忆。
            请正常与用户对话，但不要引用任何历史信息。
            """;
    }
}
