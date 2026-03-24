package com.memsys.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memsys.llm.LlmClient;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 对话工具基类：提供统一的参数解析与文本截断能力。
 */
public abstract class BaseTool {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ObjectMapper objectMapper = new ObjectMapper();

    public abstract Optional<LlmClient.ToolDefinition> build(List<String> availableSkillNames);

    @SuppressWarnings("unchecked")
    protected String stringArg(ToolExecutionRequest request, String fieldName) {
        if (request == null || request.arguments() == null || request.arguments().isBlank()) {
            return "";
        }

        try {
            Map<String, Object> arguments = objectMapper.readValue(request.arguments(), Map.class);
            Object value = arguments.get(fieldName);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", request.arguments(), e);
            return "";
        }
    }

    protected String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "...";
    }
}

