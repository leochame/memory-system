package com.memsys.memory;

import com.memsys.llm.LlmDtos.ConversationSummaryResult;
import com.memsys.llm.LlmDtos.TopicShiftDetectionResult;
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
 * 负责在以下两种条件下自动生成摘要并落盘到 session_summaries.jsonl：
 * <ul>
 *   <li>对话达到轮次阈值（如 20 轮）</li>
 *   <li>检测到对话主题发生显著切换</li>
 * </ul>
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

    /** 主题切换检测至少需要的最低轮次（避免在前几轮频繁触发） */
    private static final int TOPIC_SHIFT_MIN_TURNS = 3;

    /** 当前会话内的对话轮次计数器 */
    private final AtomicInteger turnCounter = new AtomicInteger(0);

    /** 上次生成摘要时的轮次 */
    private volatile int lastSummarizedAtTurn = 0;

    /** 最近一次检测到的主题切换结果，供展示使用 */
    private volatile TopicShiftDetectionResult lastTopicShiftResult = null;

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
     * 获取最近一次主题切换检测结果。
     */
    public TopicShiftDetectionResult getLastTopicShiftResult() {
        return lastTopicShiftResult;
    }

    /**
     * 检测当前消息是否触发了主题切换，若切换则生成前一段话题的摘要。
     * Phase 8 #2 — 长对话主题切换时生成 topic summary。
     *
     * @param recentContext 最近几轮对话的上下文文本
     * @param currentMessage 当前用户消息
     * @return 生成的摘要文本（如有），null 表示未检测到切换或摘要生成失败
     */
    public String checkTopicShiftAndSummarize(String recentContext, String currentMessage) {
        try {
            int currentTurn = turnCounter.get();
            int turnsSinceLastSummary = currentTurn - lastSummarizedAtTurn;

            // 最低轮次保护：前几轮不做主题切换检测
            if (turnsSinceLastSummary < TOPIC_SHIFT_MIN_TURNS) {
                return null;
            }

            // 上下文为空时不检测
            if (recentContext == null || recentContext.isBlank()) {
                return null;
            }

            TopicShiftDetectionResult detection = extractionService.detectTopicShift(recentContext, currentMessage);
            this.lastTopicShiftResult = detection;

            if (detection == null || !detection.topic_shifted()) {
                log.debug("No topic shift detected at turn {}", currentTurn);
                return null;
            }

            log.info("Topic shift detected at turn {}: '{}' -> '{}'",
                    currentTurn, detection.previous_topic(), detection.current_topic());

            // 生成前一段话题的摘要
            return generateAndPersistSummary();

        } catch (Exception e) {
            log.warn("Topic shift detection and summarization failed", e);
            return null;
        }
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

            // 如果是主题切换触发的，记录触发原因
            TopicShiftDetectionResult shiftResult = this.lastTopicShiftResult;
            if (shiftResult != null && shiftResult.topic_shifted()) {
                record.put("trigger", "topic_shift");
                record.put("previous_topic", shiftResult.previous_topic());
                record.put("current_topic", shiftResult.current_topic());
            } else {
                record.put("trigger", "turn_threshold");
            }

            storage.appendSessionSummary(record);
            lastSummarizedAtTurn = currentTurn;

            log.info("Session summary generated: turns {}-{}, trigger={}, topics={}",
                    record.get("from_turn"), record.get("to_turn"),
                    record.get("trigger"), result.key_topics());

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
