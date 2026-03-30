package com.memsys.llm;

import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientTest {

    @Test
    void chatWithToolsExecutesRequestedToolAndReturnsFinalAnswer() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("load_skill")
                .arguments("{\"name\":\"debug\"}")
                .build();
        FakeChatModelGateway gateway = new FakeChatModelGateway(
                List.of(AiMessage.from(List.of(request)), AiMessage.from("final answer"))
        );
        AtomicInteger executionCount = new AtomicInteger();

        LlmClient client = new LlmClient(gateway, 2);
        String result = client.chatWithTools(
                "system",
                List.of(new UserMessage("hello")),
                List.of(new LlmClient.ToolDefinition(skillTool("load_skill"), toolRequest -> {
                    executionCount.incrementAndGet();
                    assertThat(toolRequest.name()).isEqualTo("load_skill");
                    assertThat(toolRequest.arguments()).isEqualTo("{\"name\":\"debug\"}");
                    return "skill-content";
                })),
                0.7
        );

        assertThat(result).isEqualTo("final answer");
        assertThat(executionCount).hasValue(1);
        assertThat(gateway.toolCalls).hasSize(2);

        List<ChatMessage> secondRound = gateway.toolCalls.get(1);
        assertThat(secondRound.get(secondRound.size() - 1))
                .isInstanceOf(ToolExecutionResultMessage.class);

        ToolExecutionResultMessage toolResult =
                (ToolExecutionResultMessage) secondRound.get(secondRound.size() - 1);
        assertThat(toolResult.toolName()).isEqualTo("load_skill");
        assertThat(toolResult.text()).isEqualTo("skill-content");
    }

    @Test
    void chatWithToolsFeedsUnknownToolFailureBackToModel() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-2")
                .name("missing_tool")
                .arguments("{}")
                .build();
        FakeChatModelGateway gateway = new FakeChatModelGateway(
                List.of(AiMessage.from(List.of(request)), AiMessage.from("fallback answer"))
        );

        LlmClient client = new LlmClient(gateway, 2);
        String result = client.chatWithTools(
                "system",
                List.of(new UserMessage("hello")),
                List.of(new LlmClient.ToolDefinition(skillTool("load_skill"), toolRequest -> "unused")),
                0.7
        );

        assertThat(result).isEqualTo("fallback answer");
        assertThat(gateway.toolCalls).hasSize(2);

        List<ChatMessage> secondRound = gateway.toolCalls.get(1);
        ToolExecutionResultMessage toolResult =
                (ToolExecutionResultMessage) secondRound.get(secondRound.size() - 1);
        assertThat(toolResult.toolName()).isEqualTo("missing_tool");
        assertThat(toolResult.text()).contains("Tool not found: missing_tool");
    }

    @Test
    void chatWithToolsShouldStopWhenMaxToolRoundsReached() {
        ToolExecutionRequest first = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("load_skill")
                .arguments("{\"name\":\"debug\"}")
                .build();
        ToolExecutionRequest second = ToolExecutionRequest.builder()
                .id("tool-2")
                .name("load_skill")
                .arguments("{\"name\":\"debug\"}")
                .build();
        FakeChatModelGateway gateway = new FakeChatModelGateway(
                List.of(
                        AiMessage.from(List.of(first)),
                        AiMessage.from("round-limit reached", List.of(second))
                )
        );
        AtomicInteger executions = new AtomicInteger();

        LlmClient client = new LlmClient(gateway, 1);
        String result = client.chatWithTools(
                "system",
                List.of(new UserMessage("hello")),
                List.of(new LlmClient.ToolDefinition(skillTool("load_skill"), req -> {
                    executions.incrementAndGet();
                    return "ok";
                })),
                0.7
        );

        assertThat(result).isEqualTo("round-limit reached");
        assertThat(executions.get()).isEqualTo(1);
        assertThat(gateway.toolCalls).hasSize(2);
    }

    @Test
    void chatRetriesTransientErrorAndEventuallySucceeds() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient.ChatModelGateway gateway = new LlmClient.ChatModelGateway() {
            @Override
            public String generateText(List<ChatMessage> messages) {
                if (calls.incrementAndGet() == 1) {
                    throw new RuntimeException("java.net.SocketException: Connection reset");
                }
                return "ok";
            }

            @Override
            public AiMessage generateWithTools(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                throw new AssertionError("generateWithTools should not be called in this test");
            }

            @Override
            public String generateStructured(ChatRequest request) {
                throw new AssertionError("generateStructured should not be called in this test");
            }
        };

        LlmClient client = new LlmClient(gateway, 2, 2, 1);
        String result = client.chat("system", List.of(new UserMessage("hello")), 0.7);

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void chatDoesNotRetryNonRetriableError() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> lastError = new AtomicReference<>("");
        LlmClient.ChatModelGateway gateway = new LlmClient.ChatModelGateway() {
            @Override
            public String generateText(List<ChatMessage> messages) {
                calls.incrementAndGet();
                RuntimeException error = new RuntimeException("invalid request format");
                lastError.set(error.getMessage());
                throw error;
            }

            @Override
            public AiMessage generateWithTools(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
                throw new AssertionError("generateWithTools should not be called in this test");
            }

            @Override
            public String generateStructured(ChatRequest request) {
                throw new AssertionError("generateStructured should not be called in this test");
            }
        };

        LlmClient client = new LlmClient(gateway, 2, 3, 1);
        String result = client.chat("system", List.of(new UserMessage("hello")), 0.7);

        assertThat(result).contains("抱歉，我遇到了一些问题");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(lastError.get()).contains("invalid request");
    }

    private static ToolSpecification skillTool(String name) {
        return ToolSpecification.builder()
                .name(name)
                .description("load one skill")
                .addParameter(
                        "name",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("skill name")
                )
                .build();
    }

    private static final class FakeChatModelGateway implements LlmClient.ChatModelGateway {

        private final Deque<AiMessage> toolResponses;
        private final List<List<ChatMessage>> toolCalls = new ArrayList<>();

        private FakeChatModelGateway(List<AiMessage> toolResponses) {
            this.toolResponses = new ArrayDeque<>(toolResponses);
        }

        @Override
        public String generateText(List<ChatMessage> messages) {
            throw new AssertionError("generateText should not be called in this test");
        }

        @Override
        public AiMessage generateWithTools(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
            toolCalls.add(List.copyOf(messages));
            return toolResponses.removeFirst();
        }

        @Override
        public String generateStructured(ChatRequest request) {
            throw new AssertionError("generateStructured should not be called in this test");
        }
    }
}
