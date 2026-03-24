package com.memsys.memory;

import com.memsys.llm.LlmExtractionService;
import com.memsys.llm.LlmDtos.ExampleItem;
import com.memsys.llm.LlmDtos.SkillGenerationResult;
import com.memsys.memory.model.Memory;
import com.memsys.memory.storage.MemoryStorage;
import com.memsys.rag.RagService;
import com.memsys.skill.SkillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class NightlyMemoryExtractionJob {

    private final MemoryExtractor memoryExtractor;
    private final MemoryStorage storage;
    private final MemoryWriteService memoryWriteService;
    private final MemoryAsyncService memoryAsync;
    private final LlmExtractionService llmExtractionService;
    private final SkillService skillService;
    private final RagService ragService;

    public NightlyMemoryExtractionJob(
            MemoryExtractor memoryExtractor,
            MemoryStorage storage,
            MemoryWriteService memoryWriteService,
            MemoryAsyncService memoryAsync,
            LlmExtractionService llmExtractionService,
            SkillService skillService,
            RagService ragService
    ) {
        this.memoryExtractor = memoryExtractor;
        this.storage = storage;
        this.memoryWriteService = memoryWriteService;
        this.memoryAsync = memoryAsync;
        this.llmExtractionService = llmExtractionService;
        this.skillService = skillService;
        this.ragService = ragService;
    }

    @Scheduled(cron = "${scheduling.nightly-extraction-cron:0 0 2 * * ?}")
    public void nightlyMemoryExtraction() {
        memoryAsync.submit("nightly_memory_extraction", () -> {
            log.info("开始执行夜间记忆提取任务");

            try {
                LocalDateTime startDate = LocalDateTime.now().minusDays(7);
                List<Map<String, Object>> recentHistory = storage.getHistory(startDate, null);

                if (recentHistory.isEmpty()) {
                    log.info("没有新的对话历史，跳过记忆提取");
                    return;
                }

                // 提取用户档案卡（Phase 9 增强：启用冲突检测，隐式提取不直接覆盖已有记忆）
                List<Map<String, Object>> userInsights = memoryExtractor.extractUserInsights(recentHistory, "scheduled");
                for (Map<String, Object> insight : userInsights) {
                    memoryWriteService.saveMemoryWithGovernance(
                        (String) insight.get("slot_name"),
                        (String) insight.get("content"),
                        Memory.MemoryType.USER_INSIGHT,
                        Memory.SourceType.IMPLICIT,
                        (String) insight.getOrDefault("confidence", "medium"),
                        Memory.MemoryStatus.ACTIVE,
                        true  // 启用冲突检测：已有同 slot 且内容不同时，写入 pending 队列
                    );
                }

                log.info("夜间记忆提取任务完成: insights={}", userInsights.size());

                // Skill 生成
                try {
                    Optional<SkillGenerationResult> skillResult = llmExtractionService.generateSkill(recentHistory);
                    if (skillResult.isPresent()) {
                        SkillGenerationResult skill = skillResult.get();
                        skillService.saveSkill(skill.skill_name(), skill.skill_content());
                        log.info("夜间任务生成 skill: {}", skill.skill_name());
                    }
                } catch (Exception e) {
                    log.warn("夜间 skill 生成失败", e);
                }

                // Example 提取
                try {
                    List<ExampleItem> examples = llmExtractionService.extractExamples(recentHistory);
                    for (ExampleItem example : examples) {
                        ragService.indexExample(example);
                    }
                    if (!examples.isEmpty()) {
                        log.info("夜间任务提取 {} 个 examples", examples.size());
                    }
                } catch (Exception e) {
                    log.warn("夜间 example 提取失败", e);
                }
            } catch (Exception e) {
                log.error("夜间记忆提取任务失败", e);
            }
        });
    }
}
