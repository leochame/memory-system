package com.memsys.eval.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 单次评测结果 — 记录一个问题在"有记忆"和"无记忆"两种模式下的回复及评分。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalResult {

    /** 评测使用的问题 */
    private String question;

    /** 有记忆模式的回复 */
    private String responseWithMemory;

    /** 无记忆模式的回复 */
    private String responseWithoutMemory;

    /** 有记忆模式的各维度评分（relevance/personalization/accuracy/helpfulness → 1-10） */
    private Map<String, Integer> scoresWithMemory;

    /** 无记忆模式的各维度评分 */
    private Map<String, Integer> scoresWithoutMemory;

    /** 有记忆模式总分 */
    private double totalScoreWithMemory;

    /** 无记忆模式总分 */
    private double totalScoreWithoutMemory;

    /** 记忆提升率（百分比） */
    private double improvementPercent;

    /** 评测时间 */
    private LocalDateTime timestamp;

    /**
     * 计算并设置总分和提升率。
     */
    public void computeDerivedFields() {
        this.totalScoreWithMemory = scoresWithMemory == null ? 0 :
                scoresWithMemory.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        this.totalScoreWithoutMemory = scoresWithoutMemory == null ? 0 :
                scoresWithoutMemory.values().stream().mapToInt(Integer::intValue).average().orElse(0);

        if (totalScoreWithoutMemory > 0) {
            this.improvementPercent = ((totalScoreWithMemory - totalScoreWithoutMemory) / totalScoreWithoutMemory) * 100;
        } else {
            this.improvementPercent = totalScoreWithMemory > 0 ? 100.0 : 0.0;
        }
    }
}
