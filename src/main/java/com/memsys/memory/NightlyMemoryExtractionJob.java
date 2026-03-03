package com.memsys.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class NightlyMemoryExtractionJob {

    private final MemoryExtractor memoryExtractor;
    private final MemoryStorage storage;
    private final MemoryManager memoryManager;
    private final MemoryAsyncService memoryAsync;

    public NightlyMemoryExtractionJob(
            MemoryExtractor memoryExtractor,
            MemoryStorage storage,
            MemoryManager memoryManager,
            MemoryAsyncService memoryAsync
    ) {
        this.memoryExtractor = memoryExtractor;
        this.storage = storage;
        this.memoryManager = memoryManager;
        this.memoryAsync = memoryAsync;
    }

    /**
     * 每天凌晨 2 点执行隐式记忆提取
     */
    @Scheduled(cron = "${scheduling.nightly-extraction-cron:0 0 2 * * ?}")
    public void nightlyMemoryExtraction() {
        // 放入同一个单线程池，避免与对话链路的记忆写入并发冲突
        memoryAsync.submit("nightly_memory_extraction", () -> {
            log.info("开始执行夜间记忆提取任务");

            try {
                // 获取最近的对话历史
                LocalDateTime startDate = LocalDateTime.now().minusDays(7);
                List<Map<String, Object>> recentHistory = storage.getHistory(startDate, null);

                if (recentHistory.isEmpty()) {
                    log.info("没有新的对话历史，跳过记忆提取");
                    return;
                }

                // 提取用户档案卡
                List<Map<String, Object>> userInsights = memoryExtractor.extractUserInsights(recentHistory, "scheduled");
                for (Map<String, Object> insight : userInsights) {
                    saveMemory(insight);
                }

                // 提取显著话题
                List<Map<String, Object>> highlights = memoryExtractor.extractNotableHighlights(recentHistory);
                for (Map<String, Object> highlight : highlights) {
                    saveMemory(highlight);
                }

                // 生成对话摘要（每周一次）
                if (LocalDateTime.now().getDayOfWeek().getValue() == 1) {
                    List<Map<String, Object>> summaries = memoryExtractor.summarizeConversations(recentHistory);
                    for (Map<String, Object> summary : summaries) {
                        saveMemory(summary);
                    }
                }

                log.info("夜间记忆提取任务完成");
            } catch (Exception e) {
                log.error("夜间记忆提取任务失败", e);
            }
        });
    }

    private void saveMemory(Map<String, Object> memoryData) {
        String slotName = (String) memoryData.get("slot_name");
        String content = (String) memoryData.get("content");
        String memoryType = (String) memoryData.get("memory_type");

        Memory memory = new Memory();
        memory.setContent(content);
        memory.setMemoryType(Memory.MemoryType.valueOf(memoryType.toUpperCase()));
        memory.setSource(Memory.SourceType.IMPLICIT);
        memory.setHitCount(0);
        memory.setCreatedAt(LocalDateTime.now());
        memory.setLastAccessed(LocalDateTime.now());
        memory.setConfidence((String) memoryData.getOrDefault("confidence", "medium"));

        if (memory.getMemoryType() == Memory.MemoryType.MODEL_SET_CONTEXT) {
            storage.writeModelSetContext(slotName, memory);
        } else {
            storage.writeImplicitMemory(slotName, memory);
        }

        memoryManager.updateAccessTime(slotName);
        log.info("已保存隐式记忆: {} -> {}", slotName, content);
    }
}
