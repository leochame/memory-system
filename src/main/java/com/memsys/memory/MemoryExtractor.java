package com.memsys.memory;

import com.memsys.llm.LlmExtractionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
public class MemoryExtractor {

    private final LlmExtractionService extractionService;

    public MemoryExtractor(LlmExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    public Map<String, Object> extractExplicitMemory(String userMessage) {
        return extractionService.extractExplicitMemory(userMessage);
    }

    public List<Map<String, Object>> extractUserInsights(
            List<Map<String, Object>> conversationHistory,
            String trigger
    ) {
        if (conversationHistory.isEmpty()) {
            return List.of();
        }

        log.info("Extracting user insights, trigger: {}", trigger);
        List<Map<String, Object>> insights = extractionService.extractUserInsights(conversationHistory);

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
}
