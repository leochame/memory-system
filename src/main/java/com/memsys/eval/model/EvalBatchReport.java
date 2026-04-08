package com.memsys.eval.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量评测报告：聚合一轮 benchmark 的结果，便于 CLI 展示与文档归档。
 */
public record EvalBatchReport(
        LocalDateTime timestamp,
        int totalQuestions,
        int completedQuestions,
        double averageScoreWithoutMemory,
        double averageScoreWithMemory,
        double averageImprovementPercent,
        String bestQuestion,
        double bestImprovementPercent,
        String worstQuestion,
        double worstImprovementPercent,
        List<EvalResult> results
) {
}
