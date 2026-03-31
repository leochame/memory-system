package com.memsys.memory;

import com.memsys.llm.LlmDtos.MemoryReflectionResult;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.model.ReflectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Memory Reflection Service — Phase 7 核心组件。
 * <p>
 * 在模型生成回答之前，先判断当前用户消息是否真的需要长期记忆支持。
 * 目的是让系统从"有记忆就机械调用"进化为"理解为什么需要记忆"。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>反思失败不阻断主链路，返回 fallback（默认加载记忆）</li>
 *   <li>通过 LlmExtractionService 结构化输出实现判断</li>
 *   <li>结果可被后续证据视图（Memory Evidence Trace）消费</li>
 * </ul>
 */
@Slf4j
@Service
public class MemoryReflectionService {

    private final LlmExtractionService extractionService;

    public MemoryReflectionService(LlmExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    /**
     * 对当前用户消息执行记忆反思，判断是否需要长期记忆。
     *
     * @param userMessage 当前用户输入
     * @param recentContext 最近几轮对话的摘要（可为空）
     * @return ReflectionResult，包含是否需要记忆、原因和证据用途
     */
    public ReflectionResult reflect(String userMessage, String recentContext) {
        try {
            String instruction = buildReflectionPrompt(userMessage, recentContext);

            MemoryReflectionResult result = extractionService.reflectMemoryNeed(instruction);

            if (result == null) {
                log.warn("Memory reflection returned null, using fallback");
                return ReflectionResult.fallback();
            }

            ReflectionResult reflectionResult = normalizeResult(result);

            log.info("Memory reflection: needs_memory={}, reason={}, purposes={}",
                    reflectionResult.needs_memory(),
                    reflectionResult.reason(),
                    reflectionResult.evidence_purposes());

            return reflectionResult;

        } catch (Exception e) {
            log.warn("Memory reflection failed, using fallback", e);
            return ReflectionResult.fallback();
        }
    }

    private ReflectionResult normalizeResult(MemoryReflectionResult raw) {
        boolean needsMemory = raw.needs_memory();
        String memoryPurpose = normalizeMemoryPurpose(raw.memory_purpose(), needsMemory);
        String reason = normalizeReason(raw.reason(), needsMemory);
        double confidence = normalizeConfidence(raw.confidence());
        String retrievalHint = normalizeRetrievalHint(raw.retrieval_hint(), needsMemory);
        List<String> evidenceTypes = normalizeEvidenceTypes(raw.evidence_types(), needsMemory);
        List<String> purposes = normalizePurposes(raw.evidence_purposes(), needsMemory);
        if (purposes.isEmpty() && needsMemory) {
            purposes = purposesFromMemoryPurpose(memoryPurpose);
        }
        return new ReflectionResult(needsMemory, memoryPurpose, reason, confidence, retrievalHint, evidenceTypes, purposes);
    }

    private String normalizeMemoryPurpose(String memoryPurpose, boolean needsMemory) {
        if (!needsMemory) {
            return "NOT_NEEDED";
        }
        String normalized = memoryPurpose == null ? "" : memoryPurpose.trim().toUpperCase(Locale.ROOT);
        if (ReflectionResult.KNOWN_MEMORY_PURPOSES.contains(normalized)) {
            return normalized;
        }
        return "CONTINUITY";
    }

    private String normalizeReason(String reason, boolean needsMemory) {
        String normalized = reason == null ? "" : reason.trim();
        if (!normalized.isBlank()) {
            return normalized;
        }
        return needsMemory ? "需要调用长期记忆以保证回答质量。" : "当前问题可直接回答，无需调用长期记忆。";
    }

    private double normalizeConfidence(Double confidence) {
        if (confidence == null) {
            return 0.7d;
        }
        double value = confidence;
        if (value > 1.0d) {
            value = value / 100.0d;
        }
        if (value < 0.0d) {
            return 0.0d;
        }
        if (value > 1.0d) {
            return 1.0d;
        }
        return value;
    }

    private String normalizeRetrievalHint(String retrievalHint, boolean needsMemory) {
        if (!needsMemory) {
            return "";
        }
        String normalized = retrievalHint == null ? "" : retrievalHint.trim();
        if (!normalized.isBlank()) {
            return normalized;
        }
        return "优先检索与用户当前问题最相关的历史证据。";
    }

    private List<String> normalizeEvidenceTypes(List<String> evidenceTypes, boolean needsMemory) {
        if (!needsMemory) {
            return List.of();
        }
        List<String> normalized = evidenceTypes == null ? List.of() : evidenceTypes.stream()
                .map(type -> type == null ? "" : type.trim().toUpperCase(Locale.ROOT))
                .filter(type -> !type.isBlank() && ReflectionResult.KNOWN_EVIDENCE_TYPES.contains(type))
                .distinct()
                .toList();
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return List.of("USER_INSIGHT", "RECENT_HISTORY");
    }

    private List<String> normalizePurposes(List<String> purposes, boolean needsMemory) {
        if (!needsMemory) {
            return List.of();
        }
        List<String> normalized = purposes == null ? List.of() : purposes.stream()
                .map(p -> p == null ? "" : p.trim().toLowerCase(Locale.ROOT))
                .filter(p -> !p.isBlank() && ReflectionResult.KNOWN_PURPOSES.contains(p))
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            // needs_memory=true 但没有有效用途时提供稳定回退，避免后续证据链路失焦。
            return List.of("continuity");
        }
        return normalized;
    }

    private List<String> purposesFromMemoryPurpose(String memoryPurpose) {
        return switch (memoryPurpose) {
            case "PERSONALIZATION" -> List.of("personalization");
            case "CONTINUITY" -> List.of("continuity");
            case "CONSTRAINT" -> List.of("constraint");
            case "EXPERIENCE_REUSE" -> List.of("experience");
            case "ACTION_FOLLOWUP" -> List.of("followup");
            default -> List.of("continuity");
        };
    }

    private String buildReflectionPrompt(String userMessage, String recentContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是一个记忆系统的反思模块。你的任务是判断当前用户消息是否需要调用长期记忆来生成高质量回答。

                判断标准：
                - 如果问题是通用知识、简单问候、数学计算等不依赖用户历史的问题 → needs_memory=false
                - 如果问题涉及用户个人偏好、之前的对话、历史任务、个人背景 → needs_memory=true
                - 如果问题需要延续之前的讨论或遵循用户声明的约束 → needs_memory=true
                - 如果问题可能受益于历史解决方案的复用 → needs_memory=true

                memory_purpose 只能从以下枚举中选择一个：
                - PERSONALIZATION
                - CONTINUITY
                - CONSTRAINT
                - EXPERIENCE_REUSE
                - ACTION_FOLLOWUP
                - NOT_NEEDED

                confidence 为 0-100 的整数，表示判断置信度。
                retrieval_hint 给出一句检索提示语；若 needs_memory=false 则为空字符串。
                evidence_types 从以下枚举中选择（可多选，若 needs_memory=false 则为空数组）：
                - USER_INSIGHT
                - SESSION_SUMMARY
                - TASK
                - EXAMPLE
                - SKILL
                - RECENT_HISTORY

                evidence_purposes 从以下选项中选择（可多选，如果 needs_memory=false 则为空数组）：
                - personalization: 需要用户偏好来个性化回复
                - continuity: 需要延续之前的对话上下文
                - constraint: 需要遵循用户声明的约束或限制
                - experience: 需要复用历史解决方案或案例
                - followup: 需要跟进之前的任务或承诺

                """);

        if (recentContext != null && !recentContext.isBlank()) {
            sb.append("最近对话摘要：\n").append(recentContext.trim()).append("\n\n");
        }

        sb.append("当前用户消息：\n").append(userMessage);

        return sb.toString();
    }
}
