package com.memsys.memory.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryEvidenceTraceTest {

    @Test
    void buildDisplaySummaryShouldContainRetrievedAndUsedEvidenceItems() {
        MemoryEvidenceTrace trace = new MemoryEvidenceTrace(
                LocalDateTime.of(2026, 3, 29, 11, 0),
                "帮我继续任务复盘",
                new ReflectionResult(
                        true,
                        "ACTION_FOLLOWUP",
                        "需要延续任务上下文",
                        0.9d,
                        "优先检索任务上下文",
                        List.of("TASK", "RECENT_HISTORY"),
                        List.of("continuity", "followup")
                ),
                true,
                List.of("diet: 用户不爱吃鱼", "project: 记忆系统开发中"),
                List.of("project: 记忆系统开发中"),
                List.of("排查流程案例"),
                List.of("排查流程案例"),
                List.of("debugging"),
                List.of("debugging"),
                List.of("[到期] 周会 @ 2026-03-29 20:00"),
                List.of("[到期] 周会 @ 2026-03-29 20:00"),
                "insights 1/2, examples 1/1, skills 1/1, tasks 1/1"
        );

        String summary = trace.buildDisplaySummary();
        assertThat(summary)
                .contains("记忆目的: ACTION_FOLLOWUP")
                .contains("== Evidence Retrieved ==")
                .contains("retrieved_insights")
                .contains("retrieved_examples")
                .contains("loaded_skills")
                .contains("retrieved_tasks")
                .contains("== Evidence Used ==")
                .contains("覆盖率: Insights 50.0% | Examples 100.0% | Skills 100.0% | Tasks 100.0%")
                .contains("used_insights")
                .contains("used_examples")
                .contains("used_skills")
                .contains("used_tasks")
                .contains("摘要: insights 1/2, examples 1/1, skills 1/1, tasks 1/1");
    }

    @Test
    void buildDisplaySummaryShouldShowNaWhenNoRetrievedEvidence() {
        MemoryEvidenceTrace trace = new MemoryEvidenceTrace(
                LocalDateTime.of(2026, 3, 29, 12, 0),
                "你好",
                new ReflectionResult(
                        false,
                        "NOT_NEEDED",
                        "当前问题可直接回答，无需调用长期记忆。",
                        0.95d,
                        "",
                        List.of(),
                        List.of()
                ),
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "反思判断不需要记忆，已跳过长期证据加载。"
        );

        String summary = trace.buildDisplaySummary();
        assertThat(summary).contains("覆盖率: Insights n/a | Examples n/a | Skills n/a | Tasks n/a");
    }

    @Test
    void buildDisplaySummaryShouldUseDotDecimalRegardlessOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            MemoryEvidenceTrace trace = new MemoryEvidenceTrace(
                    LocalDateTime.of(2026, 3, 29, 12, 5),
                    "继续复盘",
                    new ReflectionResult(
                            true,
                            "CONTINUITY",
                            "需要延续上下文",
                            0.8d,
                            "优先检索近期上下文",
                            List.of("RECENT_HISTORY"),
                            List.of("continuity")
                    ),
                    true,
                    List.of("i1", "i2"),
                    List.of("i1"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "insights 1/2"
            );

            String summary = trace.buildDisplaySummary();
            assertThat(summary).contains("Insights 50.0%");
            assertThat(summary).doesNotContain("Insights 50,0%");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void buildDisplaySummaryShouldAppendCoverageDiagnosticsWhenUsageIsLow() {
        MemoryEvidenceTrace trace = new MemoryEvidenceTrace(
                LocalDateTime.of(2026, 3, 31, 16, 0),
                "继续诊断记忆召回",
                new ReflectionResult(
                        true,
                        "CONTINUITY",
                        "需要结合历史上下文",
                        0.88d,
                        "优先检索最近一周相关任务",
                        List.of("TASK", "USER_INSIGHT"),
                        List.of("continuity")
                ),
                true,
                List.of("i1", "i2"),
                List.of(),
                List.of("e1", "e2", "e3"),
                List.of("e1"),
                List.of("s1"),
                List.of(),
                List.of("t1"),
                List.of("t1"),
                "insights 0/2, examples 1/3, skills 0/1, tasks 1/1"
        );

        String summary = trace.buildDisplaySummary();
        assertThat(summary).contains("判断置信度: 0.88");
        assertThat(summary).contains("检索提示: 优先检索最近一周相关任务");
        assertThat(summary).contains("证据类型: TASK, USER_INSIGHT");
        assertThat(summary).contains("诊断: Insights 已检索但未使用（0/2）");
        assertThat(summary).contains("诊断: Examples 使用偏低（1/3）");
        assertThat(summary).contains("诊断: Skills 已检索但未使用（0/1）");
        assertThat(summary).contains("unused_insights");
        assertThat(summary).contains("unused_examples");
        assertThat(summary).contains("unused_skills");
    }
}
