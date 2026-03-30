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
        String reason,
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

    /**
     * 反思失败时的默认结果：需要记忆，原因为"反思阶段失败，默认加载记忆"。
     */
    public static ReflectionResult fallback() {
        return new ReflectionResult(true, "反思阶段异常，默认加载长期记忆以保证回答稳定性。", List.of("continuity"));
    }
}
