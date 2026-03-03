package com.memsys.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class LlmClient {

    private final ChatLanguageModel model;
        private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmClient(
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.model-name}") String modelName
    ) {
        this.model = OpenAiChatModel.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .modelName(modelName)
            .build();
        log.info("LLM client initialized with model: {}", modelName);
    }

    /**
     * 首选的聊天入口：直接接收 LangChain4j 的 ChatMessage 列表。
     */
    public String chat(String systemPrompt, List<ChatMessage> messages, double temperature) {
        List<ChatMessage> finalMessages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            finalMessages.add(new SystemMessage(systemPrompt));
        }

        if (messages != null && !messages.isEmpty()) {
            finalMessages.addAll(messages);
        }

        try {
            return model.generate(finalMessages).content().text();
        } catch (Exception e) {
            log.error("Failed to chat with LLM", e);
            return "抱歉，我遇到了一些问题，请稍后再试。";
        }
    }

    /**
     * 兼容旧实现的辅助方法：从 Map 结构转换为 ChatMessage 列表后再调用主入口。
     * 目前仅在本类内部使用。
     */
    private String chatWithRoleMaps(String systemPrompt, List<Map<String, String>> messages, double temperature) {
        List<ChatMessage> chatMessages = new ArrayList<>();

        if (messages != null) {
            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");

                if ("user".equals(role)) {
                    chatMessages.add(new UserMessage(content));
                } else if ("assistant".equals(role)) {
                    chatMessages.add(new AiMessage(content));
                }
            }
        }

        return chat(systemPrompt, chatMessages, temperature);
    }

    public Map<String, Object> extractExplicitMemory(String userMessage) {
        String prompt = """
            分析以下用户消息，判断是否包含需要记住的显式信息（如用户偏好、个人信息等）。

            如果包含需要记住的信息，返回 JSON 格式：
            {
              "has_memory": true,
              "slot_name": "槽位名称（如 diet_preference）",
              "content": "记忆内容描述",
              "memory_type": "model_set_context 或 user_insight",
              "source": "explicit"
            }

            如果不包含，返回：
            {
              "has_memory": false
            }

            用户消息：%s
            """.formatted(userMessage);

        String response = chatWithRoleMaps(null, List.of(Map.of("role", "user", "content", prompt)), 0.3);

        try {
            // 从回复中提取 JSON 对象并用 Jackson 解析，避免手写正则
            String jsonObject = extractJsonObject(response);
            if (jsonObject != null) {
                Map<String, Object> parsed = objectMapper.readValue(
                    jsonObject, new TypeReference<Map<String, Object>>() {});

                Object hasMemory = parsed.get("has_memory");
                if (Boolean.TRUE.equals(hasMemory)) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("has_memory", true);
                    result.put("slot_name", parsed.getOrDefault("slot_name", ""));
                    result.put("content", parsed.getOrDefault("content", ""));
                    result.put("memory_type", parsed.getOrDefault("memory_type", ""));
                    result.put("source", "explicit");
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse explicit memory JSON", e);
        }

        // 兜底：无记忆
        return Map.of("has_memory", false);
    }

    public List<Map<String, Object>> summarizeConversations(List<Map<String, Object>> conversationHistory) {
        String historyText = conversationHistory.stream()
            .map(entry -> String.format("[%s] %s: %s",
                entry.get("timestamp"),
                entry.get("role"),
                entry.get("message")))
            .collect(Collectors.joining("\n"));

        String prompt = """
            请对以下对话历史进行总结，提取关键主题和重要信息。

            返回 JSON 数组格式：
            [
              {
                "slot_name": "conversation_summary_YYYY_MM",
                "content": "摘要内容"
              }
            ]

            对话历史：
            %s
            """.formatted(historyText);

        String response = chatWithRoleMaps(null, List.of(Map.of("role", "user", "content", prompt)), 0.5);

        // 优先尝试解析为 JSON 数组，保持结构化输出
        try {
            String jsonArray = extractJsonArray(response);
            if (jsonArray != null && !jsonArray.trim().isEmpty()) {
                List<Map<String, Object>> summaries = objectMapper.readValue(
                    jsonArray, new TypeReference<List<Map<String, Object>>>() {});
                if (!summaries.isEmpty()) {
                    return summaries;
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse conversation summaries JSON", e);
        }

        // 兜底：至少返回一个文本摘要，避免影响后续流程
        Map<String, Object> summary = new HashMap<>();
        summary.put("slot_name", "conversation_summary_" + java.time.LocalDate.now().toString().replace("-", "_"));
        summary.put("content", response);
        return List.of(summary);
    }

    public List<Map<String, Object>> extractUserInsights(List<Map<String, Object>> conversationHistory) {
        String historyText = conversationHistory.stream()
            .limit(100)
            .map(entry -> String.format("%s: %s", entry.get("role"), entry.get("message")))
            .collect(Collectors.joining("\n"));

        String prompt = """
            从以下对话中提取用户的个人信息和偏好，构建用户档案。

            返回 JSON 数组，每条信息一个独立槽位：
            [
              {
                "slot_name": "home_city",
                "content": "用户住在某城市",
                "confidence": "high"
              }
            ]

            如果没有发现任何用户信息，返回空数组：[]

            对话历史：
            %s
            """.formatted(historyText);

        String response = chatWithRoleMaps(null, List.of(Map.of("role", "user", "content", prompt)), 0.3);

        try {
            // 提取JSON数组部分
            String jsonArray = extractJsonArray(response);
            if (jsonArray != null && !jsonArray.trim().equals("[]")) {
                List<Map<String, Object>> insights = objectMapper.readValue(
                    jsonArray, new TypeReference<List<Map<String, Object>>>() {});
                log.info("Extracted {} user insights", insights.size());
                return insights;
            }
        } catch (Exception e) {
            log.error("Failed to parse user insights JSON", e);
        }

        return new ArrayList<>();
    }

    public List<Map<String, Object>> analyzeTopics(List<Map<String, Object>> conversationHistory) {
        String historyText = conversationHistory.stream()
            .limit(50)
            .map(entry -> String.format("%s: %s", entry.get("role"), entry.get("message")))
            .collect(Collectors.joining("\n"));

        String prompt = """
            分析以下对话，提取用户频繁讨论的主题和话题。

            返回 JSON 数组：
            [
              {
                "slot_name": "topic_name",
                "content": "话题描述"
              }
            ]

            如果没有发现明显的话题，返回空数组：[]

            对话历史：
            %s
            """.formatted(historyText);

        String response = chatWithRoleMaps(null, List.of(Map.of("role", "user", "content", prompt)), 0.5);

        try {
            String jsonArray = extractJsonArray(response);
            if (jsonArray != null && !jsonArray.trim().equals("[]")) {
                List<Map<String, Object>> topics = objectMapper.readValue(
                    jsonArray, new TypeReference<List<Map<String, Object>>>() {});
                log.info("Extracted {} notable topics", topics.size());
                return topics;
            }
        } catch (Exception e) {
            log.error("Failed to parse topics JSON", e);
        }

        return new ArrayList<>();
    }

    private String extractJsonArray(String response) {
        // 从响应中提取JSON数组部分（处理LLM可能在JSON前后添加说明文字的情况）
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start != -1 && end != -1 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    private String extractJsonObject(String response) {
        // 允许 LLM 在 JSON 前后加说明文字，这里只尝试提取第一个完整的 { ... } 块
        int start = response.indexOf('{');
        if (start == -1) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return response.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
