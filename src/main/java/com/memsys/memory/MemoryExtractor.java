package com.memsys.memory;

import com.memsys.llm.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
public class MemoryExtractor {

    private final LlmClient llmClient;

    public MemoryExtractor(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 从用户消息中提取显式记忆
     */
    public Map<String, Object> extractExplicitMemory(String userMessage) {
        return llmClient.extractExplicitMemory(userMessage);
    }

    /**
     * 对一段时间/一批对话进行自动摘要，生成 Model Set Context 条目
     */
    public List<Map<String, Object>> summarizeConversations(List<Map<String, Object>> conversationHistory) {
        if (conversationHistory.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> summaries = llmClient.summarizeConversations(conversationHistory);

        // 为每个摘要添加元数据
        for (Map<String, Object> summary : summaries) {
            summary.putIfAbsent("memory_type", "model_set_context");
            summary.putIfAbsent("source", "explicit");
            summary.putIfAbsent("hit_count", 0);
            summary.putIfAbsent("created_at", LocalDateTime.now());
            summary.putIfAbsent("last_accessed", LocalDateTime.now());
        }

        return summaries;
    }

    /**
     * 从对话历史中提取用户档案卡（User Insights）
     */
    public List<Map<String, Object>> extractUserInsights(
            List<Map<String, Object>> conversationHistory,
            String trigger
    ) {
        if (conversationHistory.isEmpty()) {
            return List.of();
        }

        log.info("Extracting user insights, trigger: {}", trigger);
        List<Map<String, Object>> insights = llmClient.extractUserInsights(conversationHistory);

        // 为每个 insight 添加元数据
        for (Map<String, Object> insight : insights) {
            insight.putIfAbsent("memory_type", "user_insight");
            insight.putIfAbsent("source", "implicit");
            insight.putIfAbsent("hit_count", 0);
            insight.putIfAbsent("created_at", LocalDateTime.now());
            insight.putIfAbsent("last_accessed", LocalDateTime.now());
            insight.putIfAbsent("confidence", "medium");
        }

        return insights;
    }

    /**
     * 提取显著话题（Notable Highlights）
     */
    public List<Map<String, Object>> extractNotableHighlights(List<Map<String, Object>> conversationHistory) {
        if (conversationHistory.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> highlights = llmClient.analyzeTopics(conversationHistory);

        for (Map<String, Object> highlight : highlights) {
            highlight.putIfAbsent("memory_type", "notable_highlights");
            highlight.putIfAbsent("source", "implicit");
            highlight.putIfAbsent("hit_count", 0);
            highlight.putIfAbsent("created_at", LocalDateTime.now());
            highlight.putIfAbsent("last_accessed", LocalDateTime.now());
        }

        return highlights;
    }
}
