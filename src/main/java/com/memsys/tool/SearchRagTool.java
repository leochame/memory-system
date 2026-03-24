package com.memsys.tool;

import com.memsys.llm.LlmClient;
import com.memsys.rag.RagService;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * RAG 记忆检索工具。
 */
@Component
public class SearchRagTool extends BaseTool {

    private static final int TOOL_TEXT_PREVIEW_LIMIT = 200;

    private final RagService ragService;
    private final boolean ragEnabled;
    private final int ragMaxResults;
    private final double ragMinScore;

    public SearchRagTool(
            RagService ragService,
            @Value("${rag.enabled:true}") boolean ragEnabled,
            @Value("${rag.max-search-results:5}") int ragMaxResults,
            @Value("${rag.min-similarity-score:0.35}") double ragMinScore
    ) {
        this.ragService = ragService;
        this.ragEnabled = ragEnabled;
        this.ragMaxResults = ragMaxResults;
        this.ragMinScore = ragMinScore;
    }

    @Override
    public Optional<LlmClient.ToolDefinition> build(List<String> availableSkillNames) {
        if (!ragEnabled || ragService == null) {
            return Optional.empty();
        }

        ToolSpecification ragTool = ToolSpecification.builder()
                .name("search_rag")
                .description("按查询语句检索语义相关记忆，返回高相关度条目。")
                .addParameter(
                        "query",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("检索语句，建议使用用户当前问题中的关键表达。")
                )
                .build();

        return Optional.of(new LlmClient.ToolDefinition(ragTool, this::execute));
    }

    private String execute(ToolExecutionRequest request) {
        String query = stringArg(request, "query");
        if (query.isBlank()) {
            return "search_rag 调用失败：缺少参数 query。";
        }

        try {
            List<RagService.RelevantMemory> results = ragService.searchMemories(query, ragMaxResults, ragMinScore);
            if (results.isEmpty()) {
                return "RAG 未检索到相关记忆。";
            }

            StringBuilder out = new StringBuilder("RAG 检索结果：\n");
            for (int i = 0; i < results.size(); i++) {
                RagService.RelevantMemory mem = results.get(i);
                out.append(i + 1)
                        .append(". [")
                        .append(mem.getSlotName())
                        .append("] score=")
                        .append(Math.round(mem.getScore() * 100))
                        .append("% ")
                        .append(truncate(mem.getContent(), TOOL_TEXT_PREVIEW_LIMIT))
                        .append("\n");
            }
            log.info("Executed tool search_rag(query='{}', hits={})", query, results.size());
            return out.toString().trim();
        } catch (Exception e) {
            log.warn("search_rag execution failed for query: {}", query, e);
            return "search_rag 执行失败：" + e.getMessage();
        }
    }
}
