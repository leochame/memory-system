package com.memsys.memory.model;

import java.util.List;

/**
 * Memory Reflection 结果：判断当前用户消息是否需要长期记忆支持。
 * <p>
 * 这是 Phase 7（Memory Reflection 与证据视图）的核心数据模型。
 * 用于在回答前先反思"是否真的需要过去的信息"，
 * 避免机械地加载所有记忆，同时为后续证据视图提供数据基础。
 */
public record ReflectionResult(
        boolean needs_memory,
        String memory_purpose,
        String reason,
        double confidence,
        String retrieval_hint,
        List<String> evidence_types,
        List<String> evidence_purposes
) {

    /**
     * 证据用途枚举说明（用于 prompt 引导，不做 Java enum 强制约束）：
     * <ul>
     *   <li>personalization — 个性化回复（如饮食偏好、语言风格）</li>
     *   <li>continuity — 延续之前的对话或任务</li>
     *   <li>constraint — 遵循用户已声明的约束或限制</li>
     *   <li>experience — 复用历史解决方案或案例</li>
     *   <li>followup — 跟进之前的任务或承诺</li>
     * </ul>
     */
    public static final List<String> KNOWN_PURPOSES = List.of(
            "personalization", "continuity", "constraint", "experience", "followup"
    );
    public static final List<String> KNOWN_MEMORY_PURPOSES = List.of(
            "PERSONALIZATION", "CONTINUITY", "CONSTRAINT", "EXPERIENCE_REUSE", "ACTION_FOLLOWUP", "NOT_NEEDED"
    );
    public static final List<String> KNOWN_EVIDENCE_TYPES = List.of(
            "USER_INSIGHT", "SESSION_SUMMARY", "TASK", "EXAMPLE", "SKILL", "RECENT_HISTORY"
    );

    /**
     * 反思失败时的默认结果：需要记忆，原因为"反思阶段失败，默认加载记忆"。
     */
    public static ReflectionResult fallback() {
        return new ReflectionResult(
                true,
                "CONTINUITY",
                "反思阶段异常，默认加载长期记忆以保证回答稳定性。",
                0.50d,
                "优先检索近期会话摘要与用户洞察，确保上下文连续。",
                List.of("SESSION_SUMMARY", "USER_INSIGHT", "RECENT_HISTORY"),
                List.of("continuity")
        );
    }

    /**
     * 记忆开关关闭时的默认结果：不需要记忆，且用途列表为空。
     */
    public static ReflectionResult memoryDisabled() {
        return new ReflectionResult(
                false,
                "NOT_NEEDED",
                "记忆开关已关闭，已跳过长期记忆反思与加载。",
                1.0d,
                "",
                List.of(),
                List.of()
        );
    }

    public static List<String> defaultPurposesForMemoryPurpose(String memoryPurpose) {
        String normalized = normalizePurposeKey(memoryPurpose);
        return switch (normalized) {
            case "PERSONALIZATION" -> List.of("personalization");
            case "CONSTRAINT" -> List.of("constraint");
            case "EXPERIENCE_REUSE" -> List.of("experience");
            case "ACTION_FOLLOWUP" -> List.of("followup");
            case "NOT_NEEDED" -> List.of();
            default -> List.of("continuity");
        };
    }

    public static List<String> defaultEvidenceTypesForMemoryPurpose(String memoryPurpose) {
        String normalized = normalizePurposeKey(memoryPurpose);
        return switch (normalized) {
            case "PERSONALIZATION" -> List.of("USER_INSIGHT", "RECENT_HISTORY");
            case "CONTINUITY" -> List.of("SESSION_SUMMARY", "USER_INSIGHT", "RECENT_HISTORY");
            case "CONSTRAINT" -> List.of("USER_INSIGHT", "RECENT_HISTORY");
            case "EXPERIENCE_REUSE" -> List.of("EXAMPLE");
            case "ACTION_FOLLOWUP" -> List.of("TASK", "RECENT_HISTORY");
            case "NOT_NEEDED" -> List.of();
            default -> List.of("SESSION_SUMMARY", "USER_INSIGHT", "RECENT_HISTORY");
        };
    }

    private static String normalizePurposeKey(String memoryPurpose) {
        String normalized = canonicalMemoryPurpose(memoryPurpose);
        if (normalized == null) {
            return "CONTINUITY";
        }
        return normalized;
    }

    public static String normalizeMemoryPurpose(String memoryPurpose, boolean needsMemory) {
        if (!needsMemory) {
            return "NOT_NEEDED";
        }
        String normalized = canonicalMemoryPurpose(memoryPurpose);
        if (normalized == null || "NOT_NEEDED".equals(normalized)) {
            return "CONTINUITY";
        }
        return normalized;
    }

    private static String canonicalMemoryPurpose(String memoryPurpose) {
        if (memoryPurpose == null || memoryPurpose.isBlank()) {
            return null;
        }
        String compact = memoryPurpose.trim()
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z]", "");
        return switch (compact) {
            case "PERSONALIZATION" -> "PERSONALIZATION";
            case "CONTINUITY" -> "CONTINUITY";
            case "CONSTRAINT" -> "CONSTRAINT";
            case "EXPERIENCEREUSE" -> "EXPERIENCE_REUSE";
            case "ACTIONFOLLOWUP" -> "ACTION_FOLLOWUP";
            case "NOTNEEDED" -> "NOT_NEEDED";
            default -> null;
        };
    }
}
