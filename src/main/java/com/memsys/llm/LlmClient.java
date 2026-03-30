package com.memsys.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 通用 LLM 客户端：只负责与模型通信，不包含业务提取逻辑。
 */
@Slf4j
@Component
public class LlmClient {

    private static final Logger llmIoLog = LoggerFactory.getLogger("com.memsys.llm.io");
    private static final int LOG_TEXT_LIMIT = 8_000;
    private static final String GENERIC_ERROR_MESSAGE = "抱歉，我遇到了一些问题，请稍后再试。";
    private static final double DEFAULT_TEMPERATURE = 0.7d;

    private final ChatModelGateway modelGateway;
    private final int maxToolRounds;
    private final int maxRetryAttempts;
    private final long retryBackoffMillis;
    final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public LlmClient(
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.model-name}") String modelName,
            @Value("${llm.max-tool-rounds:4}") int maxToolRounds,
            @Value("${llm.retry.max-attempts:2}") int maxRetryAttempts,
            @Value("${llm.retry.backoff-ms:300}") long retryBackoffMillis
    ) {
        this(new OpenAiChatModelGateway(apiKey, baseUrl, modelName), maxToolRounds, maxRetryAttempts, retryBackoffMillis);
        log.info("LLM client initialized with model: {}", modelName);
    }

    LlmClient(ChatModelGateway modelGateway, int maxToolRounds) {
        this(modelGateway, maxToolRounds, 1, 0);
    }

    LlmClient(ChatModelGateway modelGateway, int maxToolRounds, int maxRetryAttempts, long retryBackoffMillis) {
        this.modelGateway = modelGateway;
        this.maxToolRounds = maxToolRounds;
        this.maxRetryAttempts = Math.max(1, maxRetryAttempts);
        this.retryBackoffMillis = Math.max(0, retryBackoffMillis);
    }

    /**
     * 首选的聊天入口：直接接收 LangChain4j 的 ChatMessage 列表。
     */
    public String chat(String systemPrompt, List<ChatMessage> messages, double temperature) {
        List<ChatMessage> finalMessages = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            finalMessages.add(new SystemMessage(systemPrompt));
        }
        if (messages != null && !messages.isEmpty()) {
            finalMessages.addAll(messages);
        }

        try {
            logLlmInput("chat", finalMessages, List.of(), null);
            String response = callWithRetry("chat", () -> generateText(finalMessages, temperature));
            logLlmOutput("chat", response);
            return response;
        } catch (Exception e) {
            log.error("Failed to chat with LLM", e);
            logLlmError("chat", e);
            return GENERIC_ERROR_MESSAGE;
        }
    }

    public String chatWithTools(String systemPrompt,
                                List<ChatMessage> messages,
                                List<ToolDefinition> toolDefinitions,
                                double temperature) {
        List<ChatMessage> conversation = composeMessages(systemPrompt, messages);
        if (toolDefinitions == null || toolDefinitions.isEmpty()) {
            return chat(systemPrompt, messages, temperature);
        }

        List<ToolSpecification> toolSpecifications = toolDefinitions.stream()
                .map(ToolDefinition::specification)
                .toList();
        Map<String, ToolDefinition> toolsByName = new LinkedHashMap<>();
        for (ToolDefinition definition : toolDefinitions) {
            toolsByName.put(definition.specification().name(), definition);
        }

        for (int round = 0; round <= maxToolRounds; round++) {
            try {
                logLlmInput("chatWithTools", conversation, toolSpecifications, round);
                AiMessage aiMessage = callWithRetry(
                        "chatWithTools",
                        () -> generateWithTools(conversation, toolSpecifications, temperature)
                );
                if (aiMessage == null) {
                    log.warn("LLM returned null AiMessage during tool chat");
                    logLlmOutput("chatWithTools", "[null ai message]");
                    return GENERIC_ERROR_MESSAGE;
                }

                logLlmAiMessage("chatWithTools", round, aiMessage);
                conversation.add(aiMessage);

                if (!aiMessage.hasToolExecutionRequests()) {
                    String text = aiMessage.text();
                    if (text != null && !text.isBlank()) {
                        logLlmOutput("chatWithTools", text);
                        return text;
                    }
                    log.warn("LLM finished tool chat without text response");
                    logLlmOutput("chatWithTools", "[blank ai message]");
                    return GENERIC_ERROR_MESSAGE;
                }

                if (round == maxToolRounds) {
                    log.warn("Tool chat exceeded max rounds: {}", maxToolRounds);
                    String text = aiMessage.text();
                    logLlmOutput("chatWithTools", text);
                    return (text != null && !text.isBlank())
                            ? text
                            : GENERIC_ERROR_MESSAGE;
                }

                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    ToolDefinition tool = toolsByName.get(request.name());
                    String result;
                    if (tool == null) {
                        result = "Tool not found: " + request.name();
                        log.warn("Model requested unknown tool: {}", request.name());
                    } else {
                        try {
                            result = tool.executor().apply(request);
                        } catch (Exception e) {
                            log.warn("Tool execution failed: {}", request.name(), e);
                            result = "Tool execution failed: " + e.getMessage();
                        }
                    }
                    logLlmToolResult(round, request, result);
                    conversation.add(ToolExecutionResultMessage.from(request, result));
                }
            } catch (Exception e) {
                log.error("Failed to chat with LLM tools", e);
                logLlmError("chatWithTools", e);
                return GENERIC_ERROR_MESSAGE;
            }
        }

        logLlmOutput("chatWithTools", "[fallback generic error]");
        return GENERIC_ERROR_MESSAGE;
    }

    private String generateText(List<ChatMessage> messages, double temperature) {
        if (modelGateway instanceof TemperatureAwareChatModelGateway temperatureAware) {
            return temperatureAware.generateText(messages, temperature);
        }
        return modelGateway.generateText(messages);
    }

    private AiMessage generateWithTools(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecifications,
            double temperature
    ) {
        if (modelGateway instanceof TemperatureAwareChatModelGateway temperatureAware) {
            return temperatureAware.generateWithTools(messages, toolSpecifications, temperature);
        }
        return modelGateway.generateWithTools(messages, toolSpecifications);
    }

    /**
     * 使用 JSON Schema 约束的结构化输出聊天。
     * Package-visible，供 LlmExtractionService 调用。
     */
    <T> T chatWithJsonSchema(String systemPrompt,
                             List<ChatMessage> messages,
                             JsonSchema jsonSchema,
                             Class<T> clazz) {
        List<ChatMessage> finalMessages = composeMessages(systemPrompt, messages);

        ChatRequest request = ChatRequest.builder()
                .messages(finalMessages)
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(jsonSchema)
                        .build())
                .build();

        logLlmInput("chatWithJsonSchema", finalMessages, List.of(), null);
        String json;
        try {
            json = callWithRetry("chatWithJsonSchema", () -> modelGateway.generateStructured(request));
        } catch (Exception e) {
            logLlmError("chatWithJsonSchema", e);
            throw new RuntimeException("Failed to call structured JSON API", e);
        }
        logLlmOutput("chatWithJsonSchema", json);
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            logLlmError("chatWithJsonSchema", e);
            throw new RuntimeException("Failed to parse structured JSON response: " + json, e);
        }
    }

    /**
     * 兼容旧实现的辅助方法：从 Map 结构转换为 ChatMessage 列表后再调用主入口。
     * Package-visible，供 LlmExtractionService 的 legacy fallback 使用。
     */
    String chatWithRoleMaps(String systemPrompt, List<Map<String, String>> messages, double temperature) {
        List<ChatMessage> chatMessages = new ArrayList<>();

        if (messages != null) {
            for (Map<String, String> msg : messages) {
                String role = msg.get("role");
                String content = msg.get("content");

                if ("user".equals(role)) {
                    chatMessages.add(new UserMessage(content));
                } else if ("assistant".equals(role)) {
                    chatMessages.add(new AiMessage(content));
                }
            }
        }

        return chat(systemPrompt, chatMessages, temperature);
    }

    private List<ChatMessage> composeMessages(String systemPrompt, List<ChatMessage> messages) {
        List<ChatMessage> finalMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            finalMessages.add(new SystemMessage(systemPrompt));
        }
        if (messages != null && !messages.isEmpty()) {
            finalMessages.addAll(messages);
        }
        return finalMessages;
    }

    // ========== JSON 提取工具方法（package-visible） ==========

    String extractJsonArray(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start != -1 && end != -1 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    String extractJsonObject(String response) {
        int start = response.indexOf('{');
        if (start == -1) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return response.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private void logLlmInput(String channel,
                             List<ChatMessage> messages,
                             List<ToolSpecification> toolSpecifications,
                             Integer round) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        if (round != null) {
            payload.put("round", round);
        }
        payload.put("messages", toLogMessages(messages));
        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            payload.put("tools", toLogTools(toolSpecifications));
        }
        logLlmIo("LLM_INPUT", payload);
    }

    private void logLlmAiMessage(String channel, int round, AiMessage aiMessage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("round", round);
        payload.put("ai_message", toLogAiMessage(aiMessage));
        logLlmIo("LLM_MODEL_OUTPUT", payload);
    }

    private void logLlmToolResult(int round, ToolExecutionRequest request, String result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("round", round);
        payload.put("tool_name", request.name());
        payload.put("tool_id", request.id());
        payload.put("tool_arguments", truncateForLog(request.arguments()));
        payload.put("tool_result", truncateForLog(result));
        logLlmIo("LLM_TOOL_RESULT", payload);
    }

    private void logLlmOutput(String channel, String output) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("output", truncateForLog(output));
        logLlmIo("LLM_OUTPUT", payload);
    }

    private void logLlmError(String channel, Exception e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("error_type", e.getClass().getName());
        payload.put("error_message", truncateForLog(e.getMessage()));
        logLlmIo("LLM_ERROR", payload);
    }

    private void logLlmIo(String event, Map<String, Object> payload) {
        if (!llmIoLog.isInfoEnabled()) {
            return;
        }
        try {
            llmIoLog.info("{} {}", event, objectMapper.writeValueAsString(payload));
        } catch (Exception ignored) {
            llmIoLog.info("{} {}", event, payload);
        }
    }

    private List<Map<String, Object>> toLogMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", message.type().name());

            if (message instanceof SystemMessage systemMessage) {
                item.put("text", truncateForLog(systemMessage.text()));
            } else if (message instanceof UserMessage userMessage) {
                String text = userMessage.hasSingleText()
                        ? userMessage.singleText()
                        : String.valueOf(userMessage.contents());
                item.put("text", truncateForLog(text));
            } else if (message instanceof ToolExecutionResultMessage toolResult) {
                item.put("tool_name", toolResult.toolName());
                item.put("tool_result", truncateForLog(toolResult.text()));
            } else if (message instanceof AiMessage aiMessage) {
                item.putAll(toLogAiMessage(aiMessage));
            } else {
                item.put("content", truncateForLog(String.valueOf(message)));
            }

            result.add(item);
        }
        return result;
    }

    private Map<String, Object> toLogAiMessage(AiMessage aiMessage) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("text", truncateForLog(aiMessage.text()));
        if (aiMessage.hasToolExecutionRequests()) {
            List<Map<String, Object>> requests = new ArrayList<>();
            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("id", request.id());
                req.put("name", request.name());
                req.put("arguments", truncateForLog(request.arguments()));
                requests.add(req);
            }
            item.put("tool_requests", requests);
        }
        return item;
    }

    private List<Map<String, Object>> toLogTools(List<ToolSpecification> toolSpecifications) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolSpecification specification : toolSpecifications) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", specification.name());
            tool.put("description", truncateForLog(specification.description()));
            tools.add(tool);
        }
        return tools;
    }

    private String truncateForLog(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= LOG_TEXT_LIMIT) {
            return raw;
        }
        int omitted = raw.length() - LOG_TEXT_LIMIT;
        return raw.substring(0, LOG_TEXT_LIMIT) + "... [truncated " + omitted + " chars]";
    }

    private <T> T callWithRetry(String channel, ThrowingSupplier<T> supplier) throws Exception {
        Exception lastError = null;
        long callStartNs = System.nanoTime();
        long accumulatedBackoffMs = 0L;
        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                T result = supplier.get();
                if (attempt > 1) {
                    long totalElapsedMs = Math.max(0L, (System.nanoTime() - callStartNs) / 1_000_000L);
                    log.info("LLM {} recovered after retry: attempts={}, retry_backoff_ms={}, total_elapsed_ms={}",
                            channel, attempt, accumulatedBackoffMs, totalElapsedMs);
                }
                return result;
            } catch (Exception e) {
                lastError = e;
                if (!isRetriable(e) || attempt >= maxRetryAttempts) {
                    throw e;
                }

                long backoff = retryBackoffMillis * attempt;
                accumulatedBackoffMs += Math.max(0L, backoff);
                log.warn("LLM {} attempt {}/{} failed with retriable error: {}. Retrying in {} ms.",
                        channel, attempt, maxRetryAttempts, e.toString(), backoff);
                if (backoff > 0) {
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("LLM retry interrupted", interrupted);
                    }
                }
            }
        }
        throw lastError == null ? new RuntimeException("Unknown LLM retry error") : lastError;
    }

    private boolean isRetriable(Exception exception) {
        for (Throwable cursor = exception; cursor != null; cursor = cursor.getCause()) {
            String className = cursor.getClass().getName().toLowerCase();
            String message = String.valueOf(cursor.getMessage()).toLowerCase();
            String text = className + " " + message;

            if (text.contains("unknownhost")
                    || text.contains("socketexception")
                    || text.contains("connectexception")
                    || text.contains("connection reset")
                    || text.contains("timed out")
                    || text.contains("timeout")
                    || text.contains("429")
                    || text.contains("rate limit")
                    || text.contains("temporarily unavailable")
                    || text.contains("service unavailable")) {
                return true;
            }
        }
        return false;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    public record ToolDefinition(
            ToolSpecification specification,
            Function<ToolExecutionRequest, String> executor
    ) {
    }

    interface ChatModelGateway {
        String generateText(List<ChatMessage> messages);

        AiMessage generateWithTools(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications);

        String generateStructured(ChatRequest request);
    }

    interface TemperatureAwareChatModelGateway extends ChatModelGateway {
        String generateText(List<ChatMessage> messages, double temperature);

        AiMessage generateWithTools(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications, double temperature);
    }

    private static final class OpenAiChatModelGateway implements TemperatureAwareChatModelGateway {

        private final String apiKey;
        private final String baseUrl;
        private final String modelName;
        private final OpenAiChatModel defaultModel;
        private final Map<String, OpenAiChatModel> temperatureModels = new ConcurrentHashMap<>();

        private OpenAiChatModelGateway(String apiKey, String baseUrl, String modelName) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.modelName = modelName;
            this.defaultModel = createModel(null);
        }

        @Override
        public String generateText(List<ChatMessage> messages) {
            return generateText(messages, DEFAULT_TEMPERATURE);
        }

        @Override
        public String generateText(List<ChatMessage> messages, double temperature) {
            return modelForTemperature(temperature).generate(messages).content().text();
        }

        @Override
        public AiMessage generateWithTools(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
            return generateWithTools(messages, toolSpecifications, DEFAULT_TEMPERATURE);
        }

        @Override
        public AiMessage generateWithTools(
                List<ChatMessage> messages,
                List<ToolSpecification> toolSpecifications,
                double temperature
        ) {
            return modelForTemperature(temperature).generate(messages, toolSpecifications).content();
        }

        @Override
        public String generateStructured(ChatRequest request) {
            return defaultModel.chat(request).aiMessage().text();
        }

        private OpenAiChatModel modelForTemperature(double temperature) {
            if (Double.isNaN(temperature) || Double.isInfinite(temperature)) {
                return defaultModel;
            }
            String key = String.format(Locale.ROOT, "%.3f", temperature);
            return temperatureModels.computeIfAbsent(key, ignored -> createModel(temperature));
        }

        private OpenAiChatModel createModel(Double temperature) {
            var builder = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .strictJsonSchema(true);
            if (temperature != null) {
                applyTemperature(builder, temperature);
            }
            return builder.build();
        }

        private void applyTemperature(Object builder, double temperature) {
            try {
                builder.getClass().getMethod("temperature", Double.class).invoke(builder, temperature);
                return;
            } catch (NoSuchMethodException ignored) {
                // Try primitive signature.
            } catch (Exception e) {
                log.debug("Failed to set LLM temperature via boxed signature", e);
                return;
            }
            try {
                builder.getClass().getMethod("temperature", double.class).invoke(builder, temperature);
            } catch (Exception e) {
                log.debug("Failed to set LLM temperature via primitive signature", e);
            }
        }
    }
}
