package com.memsys.eval;

import com.memsys.eval.model.EvalBatchReport;
import com.memsys.cli.ConversationCli;
import com.memsys.eval.model.EvalResult;
import com.memsys.llm.LlmDtos;
import com.memsys.llm.LlmExtractionService;
import com.memsys.memory.storage.MemoryStorage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Phase 10 评测服务 — "有记忆 vs 无记忆" A/B 对比评测。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>对同一个问题分别在"无记忆"和"有记忆"两种模式下获取回复</li>
 *   <li>使用 LLM 作为评审，对两个回复在四个维度上打分</li>
 *   <li>计算提升率并持久化结果</li>
 * </ol>
 * <p>
 * 评分维度：relevance（相关性）、personalization（个性化）、accuracy（准确性）、helpfulness（实用性）
 */
@Slf4j
@Service
public class EvalService {

    /** 内置评测集 — 覆盖不同类型的问题场景 */
    private static final List<String> DEFAULT_EVAL_QUESTIONS = List.of(
            "你觉得我最近关注的事情有哪些？",
            "帮我推荐一些适合我的学习资料",
            "我上次和你讨论了什么？",
            "给我一些时间管理的建议",
            "你了解我的工作习惯吗？"
    );

    private final ConversationCli conversationCli;
    private final LlmExtractionService llmExtractionService;
    private final MemoryStorage storage;

    public EvalService(ConversationCli conversationCli,
                       LlmExtractionService llmExtractionService,
                       MemoryStorage storage) {
        this.conversationCli = conversationCli;
        this.llmExtractionService = llmExtractionService;
        this.storage = storage;
    }

    /**
     * 返回内置评测集。
     */
    public List<String> getDefaultEvalQuestions() {
        return DEFAULT_EVAL_QUESTIONS;
    }

    /**
     * 返回当前默认 benchmark 题集：优先读取外部文件，若文件内容为空则回退到内置题集。
     */
    public List<String> getConfiguredBenchmarkQuestions() {
        List<String> externalQuestions = storage.readBenchmarkQuestions();
        return externalQuestions.isEmpty() ? DEFAULT_EVAL_QUESTIONS : externalQuestions;
    }

    /**
     * 运行内置 benchmark，返回批量评测报告。
     */
    public EvalBatchReport runDefaultBenchmark() {
        List<String> questions = storage.readBenchmarkQuestions();
        boolean usingExternalDataset = !questions.isEmpty();
        List<String> effectiveQuestions = usingExternalDataset ? questions : DEFAULT_EVAL_QUESTIONS;
        String datasetSource = usingExternalDataset ? ".memory/benchmark_questions.txt" : "built-in defaults";
        return runBatchEvalInternal(effectiveQuestions, datasetSource);
    }

    /**
     * 对一组问题批量执行 A/B 评测。
     */
    public EvalBatchReport runBatchEval(List<String> questions) {
        return runBatchEvalInternal(questions, "inline");
    }

    /**
     * 读取历史批次评测报告。
     */
    public List<Map<String, Object>> getRecentBatchReports(int limit) {
        return storage.readBenchmarkReports(limit);
    }

    /**
     * 对一组问题批量执行 A/B 评测，并记录题集来源。
     */
    private EvalBatchReport runBatchEvalInternal(List<String> questions, String datasetSource) {
        List<String> normalizedQuestions = questions == null
                ? List.of()
                : questions.stream()
                .map(this::normalizeQuestion)
                .filter(question -> !question.isBlank())
                .distinct()
                .toList();

        List<EvalResult> results = new ArrayList<>();
        for (String question : normalizedQuestions) {
            results.add(runSingleEval(question));
        }
        EvalBatchReport report = buildBatchReport(results, normalizedQuestions.size(), datasetSource);
        persistBatchReport(report, normalizedQuestions);
        return report;
    }

    /**
     * 对单个问题执行 A/B 对比评测。
     *
     * @param question 要评测的用户问题
     * @return 评测结果（包含两种模式的回复和评分）
     */
    public EvalResult runSingleEval(String question) {
        String normalizedQuestion = normalizeQuestion(question);
        log.info("Starting evaluation for question: {}", normalizedQuestion);

        if (normalizedQuestion.isBlank()) {
            return buildEmptyQuestionResult();
        }

        // 读取用户画像（用于评审时参考个性化维度）
        String userProfile = storage.readUserInsightsNarrative();

        // 1) 无记忆模式：使用 temporaryMode
        String responseWithoutMemory = sanitizeResponse(generateWithoutMemory(normalizedQuestion));
        log.info("No-memory response generated ({} chars)", responseWithoutMemory.length());

        // 2) 有记忆模式：使用正常对话流程
        String responseWithMemory = sanitizeResponse(generateWithMemory(normalizedQuestion));
        log.info("With-memory response generated ({} chars)", responseWithMemory.length());

        // 3) LLM 评审打分
        LlmDtos.EvalScoreResult scoreWithout = llmExtractionService.evaluateResponseQuality(
                normalizedQuestion, responseWithoutMemory, null); // 评审无记忆回复时不提供画像参考
        LlmDtos.EvalScoreResult scoreWith = llmExtractionService.evaluateResponseQuality(
                normalizedQuestion, responseWithMemory, userProfile);

        // 4) 组装结果
        EvalResult result = new EvalResult();
        result.setQuestion(normalizedQuestion);
        result.setResponseWithoutMemory(responseWithoutMemory);
        result.setResponseWithMemory(responseWithMemory);
        result.setTimestamp(LocalDateTime.now());

        result.setScoresWithoutMemory(toScoreMap(scoreWithout));
        result.setScoresWithMemory(toScoreMap(scoreWith));

        result.computeDerivedFields();

        // 5) 持久化
        persistResult(result, scoreWithout, scoreWith);

        log.info("Evaluation completed: with_memory={}, without_memory={}, improvement={}%",
                result.getTotalScoreWithMemory(),
                result.getTotalScoreWithoutMemory(),
                result.getImprovementPercent());

        return result;
    }

    /**
     * 读取历史评测结果。
     */
    public List<Map<String, Object>> getRecentResults(int limit) {
        return storage.readEvalResults(limit);
    }

    // ========== 内部方法 ==========

    private EvalBatchReport buildBatchReport(List<EvalResult> results, int totalQuestions, String datasetSource) {
        int completedQuestions = results == null ? 0 : results.size();
        if (completedQuestions == 0) {
            return new EvalBatchReport(
                    LocalDateTime.now(),
                    totalQuestions,
                    0,
                    0,
                    0,
                    0,
                    "",
                    0,
                    "",
                    0,
                    List.of(),
                    datasetSource
            );
        }

        double averageWithout = results.stream()
                .mapToDouble(EvalResult::getTotalScoreWithoutMemory)
                .average()
                .orElse(0);
        double averageWith = results.stream()
                .mapToDouble(EvalResult::getTotalScoreWithMemory)
                .average()
                .orElse(0);
        double averageImprovement = results.stream()
                .mapToDouble(EvalResult::getImprovementPercent)
                .average()
                .orElse(0);

        EvalResult best = results.stream()
                .max(Comparator.comparingDouble(EvalResult::getImprovementPercent))
                .orElse(null);
        EvalResult worst = results.stream()
                .min(Comparator.comparingDouble(EvalResult::getImprovementPercent))
                .orElse(null);

        return new EvalBatchReport(
                LocalDateTime.now(),
                totalQuestions,
                completedQuestions,
                averageWithout,
                averageWith,
                averageImprovement,
                best != null ? best.getQuestion() : "",
                best != null ? best.getImprovementPercent() : 0,
                worst != null ? worst.getQuestion() : "",
                worst != null ? worst.getImprovementPercent() : 0,
                List.copyOf(results),
                datasetSource
        );
    }

    private String generateWithoutMemory(String question) {
        return conversationCli.processUserMessageTemporaryForEval(question);
    }

    private String generateWithMemory(String question) {
        // 评测场景走只读链路，避免污染会话历史与任务通知
        return conversationCli.processUserMessageWithMemoryForEval(question);
    }

    private void persistResult(EvalResult result,
                               LlmDtos.EvalScoreResult scoreWithout,
                               LlmDtos.EvalScoreResult scoreWith) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", result.getTimestamp().toString());
        record.put("question", result.getQuestion());
        record.put("response_without_memory_length", result.getResponseWithoutMemory().length());
        record.put("response_with_memory_length", result.getResponseWithMemory().length());
        record.put("scores_without_memory", result.getScoresWithoutMemory());
        record.put("scores_with_memory", result.getScoresWithMemory());
        record.put("total_score_without_memory", result.getTotalScoreWithoutMemory());
        record.put("total_score_with_memory", result.getTotalScoreWithMemory());
        record.put("improvement_percent", result.getImprovementPercent());
        record.put("justification_without", scoreWithout != null ? scoreWithout.justification() : "");
        record.put("justification_with", scoreWith != null ? scoreWith.justification() : "");

        storage.appendEvalResult(record);
    }

    private void persistBatchReport(EvalBatchReport report, List<String> questions) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("timestamp", report.timestamp().toString());
        record.put("dataset_source", report.datasetSource());
        record.put("total_questions", report.totalQuestions());
        record.put("completed_questions", report.completedQuestions());
        record.put("average_score_without_memory", report.averageScoreWithoutMemory());
        record.put("average_score_with_memory", report.averageScoreWithMemory());
        record.put("average_improvement_percent", report.averageImprovementPercent());
        record.put("best_question", report.bestQuestion());
        record.put("best_improvement_percent", report.bestImprovementPercent());
        record.put("worst_question", report.worstQuestion());
        record.put("worst_improvement_percent", report.worstImprovementPercent());
        record.put("questions", questions == null ? List.of() : List.copyOf(questions));
        storage.appendBenchmarkReport(record);
    }

    private Map<String, Integer> toScoreMap(LlmDtos.EvalScoreResult score) {
        if (score == null) {
            return Map.of(
                    "relevance", 5,
                    "personalization", 5,
                    "accuracy", 5,
                    "helpfulness", 5
            );
        }
        return Map.of(
                "relevance", clampScore(score.relevance()),
                "personalization", clampScore(score.personalization()),
                "accuracy", clampScore(score.accuracy()),
                "helpfulness", clampScore(score.helpfulness())
        );
    }

    private int clampScore(int score) {
        if (score < 1) {
            return 1;
        }
        if (score > 10) {
            return 10;
        }
        return score;
    }

    private String sanitizeResponse(String response) {
        return response == null ? "" : response;
    }

    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }

    private EvalResult buildEmptyQuestionResult() {
        EvalResult result = new EvalResult();
        result.setQuestion("");
        result.setResponseWithoutMemory("");
        result.setResponseWithMemory("");
        result.setTimestamp(LocalDateTime.now());
        result.setScoresWithoutMemory(toScoreMap(null));
        result.setScoresWithMemory(toScoreMap(null));
        result.computeDerivedFields();
        persistResult(result, null, null);
        log.warn("Skipped evaluation because question is empty");
        return result;
    }
}
