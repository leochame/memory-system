package com.memsys.memory;

import com.memsys.llm.LlmDtos.ConversationSummaryResult;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.storage.MemoryStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Conversation Summary Service — Phase 8 核心组件。
 * <p>
 * 负责在对话达到轮次阈值时自动生成摘要，并落盘到 session_summaries.jsonl。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>摘要生成失败不阻断主链路</li>
 *   <li>通过 LlmExtractionService 结构化输出生成摘要</li>
 *   <li>摘要结果可被后续 prompt 压缩和场景展示使用</li>
 * </ul>
 */
@Slf4j
@Service
public class ConversationSummaryService {

    private final LlmExtractionService extractionService;
    private final MemoryStorage storage;
    private final int summaryThreshold;

    /** 当前会话内的对话轮次计数器 */
    private final AtomicInteger turnCounter = new AtomicInteger(0);

    /** 上次生成摘要时的轮次 */
    private volatile int lastSummarizedAtTurn = 0;

    public ConversationSummaryService(
            LlmExtractionService extractionService,
            MemoryStorage storage,
            @Value("${memory.summary-threshold:20}") int summaryThreshold
    ) {
        this.extractionService = extractionService;
        this.storage = storage;
        this.summaryThreshold = summaryThreshold;
    }

    /**
     * 通知一轮对话完成，内部计数器递增。
     * 当轮次达到阈值时返回 true，提示调用方触发摘要。
     *
     * @return true 表示应触发摘要生成
     */
    public boolean onTurnCompleted() {
        int current = turnCounter.incrementAndGet();
        int turnsSinceLastSummary = current - lastSummarizedAtTurn;
        return turnsSinceLastSummary >= summaryThreshold;
    }

    /**
     * 获取当前轮次计数。
     */
    public int getCurrentTurnCount() {
        return turnCounter.get();
    }

    /**
     * 生成会话摘要并落盘。
     * 从 conversation_history.jsonl 获取自上次摘要以来的对话历史，
     * 通过 LLM 生成摘要后写入 session_summaries.jsonl。
     *
     * @return 生成的摘要文本，失败时返回 null
     */
    public String generateAndPersistSummary() {
        try {
            int currentTurn = turnCounter.get();
            int turnsToSummarize = currentTurn - lastSummarizedAtTurn;

            if (turnsToSummarize <= 0) {
                log.info("No new turns to summarize");
                return null;
            }

            // 获取需要摘要的对话历史
            List<Map<String, Object>> recentTurns = storage.getRecentConversationTurns(turnsToSummarize);
            if (recentTurns.isEmpty()) {
                log.info("No conversation history available for summary");
                return null;
            }

            // 格式化对话为文本
            String conversationText = recentTurns.stream()
                    .map(turn -> String.format("%s [%s]: %s",
                            turn.getOrDefault("timestamp", ""),
                            turn.getOrDefault("role", "unknown"),
                            truncateMessage(String.valueOf(turn.getOrDefault("message", "")))))
                    .collect(Collectors.joining("\n"));

            // 调用 LLM 生成摘要
            ConversationSummaryResult result = extractionService.summarizeConversation(
                    conversationText, turnsToSummarize);

            if (result == null || result.summary() == null || result.summary().isBlank()) {
                log.warn("LLM returned empty summary, skipping persistence");
                return null;
            }

            // 构建落盘记录
            Map<String, Object> record = new HashMap<>();
            record.put("generated_at", LocalDateTime.now().toString());
            record.put("summary", result.summary());
            record.put("key_topics", result.key_topics() != null ? result.key_topics() : List.of());
            record.put("turn_count", result.turn_count());
            record.put("time_range", result.time_range() != null ? result.time_range() : "unknown");
            record.put("from_turn", lastSummarizedAtTurn + 1);
            record.put("to_turn", currentTurn);

            storage.appendSessionSummary(record);
            lastSummarizedAtTurn = currentTurn;

            log.info("Session summary generated: turns {}-{}, topics={}",
                    record.get("from_turn"), record.get("to_turn"), result.key_topics());

            return result.summary();

        } catch (Exception e) {
            log.error("Failed to generate conversation summary", e);
            return null;
        }
    }

    /**
     * 获取最近的会话摘要列表，用于 prompt 注入或展示。
     *
     * @param limit 最多返回条数
     * @return 摘要列表
     */
    public List<Map<String, Object>> getRecentSummaries(int limit) {
        return storage.readSessionSummaries(limit);
    }

    private String truncateMessage(String message) {
        if (message.length() > 300) {
            return message.substring(0, 300) + "...";
        }
        return message;
    }
}
