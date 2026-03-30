package com.memsys.im;

import com.memsys.cli.ConversationProgressEvent;
import com.memsys.cli.ConversationProgressListener;
import com.memsys.im.model.ImConversationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ImChatController.class)
@TestPropertySource(properties = "im.api.enabled=true")
class ImChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ImRuntimeService imRuntimeService;

    @Test
    void chatShouldReturnCollapsedProcessPayload() throws Exception {
        when(imRuntimeService.processIncoming(any(), any(), any()))
                .thenAnswer(invocation -> {
                    ConversationProgressListener listener = invocation.getArgument(1);
                    listener.onEvent(new ConversationProgressEvent(
                            "accepted",
                            "已收到消息，开始处理。",
                            Map.of(),
                            1710000000000L
                    ));
                    listener.onEvent(new ConversationProgressEvent(
                            "generating",
                            "正在调用模型生成回复。",
                            Map.of(),
                            1710000000200L
                    ));
                    return new ImConversationResult("final reply", List.of(), 222L);
                });

        String body = """
                {
                  "platform": "telegram",
                  "conversationId": "chat_1",
                  "senderId": "user_1",
                  "text": "hello",
                  "temporary": false
                }
                """;

        MvcResult result = mockMvc.perform(post("/im/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertThat(content).contains("\"ok\":true");
        assertThat(content).contains("\"reply\":\"final reply\"");
        assertThat(content).contains("\"process_collapsed\":true");
        assertThat(content).contains("\"process_steps\"");
        assertThat(content).contains("\"process_preview\"");
    }

    @Test
    void streamShouldEmitProcessFinalDoneEvents() throws Exception {
        when(imRuntimeService.processIncoming(any(), any(), any()))
                .thenAnswer(invocation -> {
                    ConversationProgressListener listener = invocation.getArgument(1);
                    listener.onEvent(new ConversationProgressEvent(
                            "accepted",
                            "已收到消息，开始处理。",
                            Map.of(),
                            1710000000000L
                    ));
                    listener.onEvent(new ConversationProgressEvent(
                            "generating",
                            "正在调用模型生成回复。",
                            Map.of(),
                            1710000000200L
                    ));
                    return new ImConversationResult("stream reply", List.of(), 333L);
                });

        String body = """
                {
                  "platform": "feishu",
                  "conversationId": "oc_1",
                  "senderId": "ou_1",
                  "text": "请总结",
                  "temporary": false
                }
                """;

        MvcResult async = mockMvc.perform(post("/im/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult done = mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn();

        String content = done.getResponse().getContentAsString();
        assertThat(content).contains("event:process");
        assertThat(content).contains("event:final");
        assertThat(content).contains("event:done");
        assertThat(content).contains("stream reply");
    }

    @Test
    void streamShouldEmitErrorEventWhenValidationFailed() throws Exception {
        String body = """
                {
                  "platform": "",
                  "conversationId": "",
                  "senderId": "",
                  "text": ""
                }
                """;

        MvcResult result = mockMvc.perform(post("/im/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("event:error");
        assertThat(content).contains("不能为空");
    }

    @Test
    void streamShouldEmitErrorEventWhenServiceThrows() throws Exception {
        when(imRuntimeService.processIncoming(any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        String body = """
                {
                  "platform": "telegram",
                  "conversationId": "chat_1",
                  "senderId": "user_1",
                  "text": "hello"
                }
                """;

        MvcResult async = mockMvc.perform(post("/im/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();

        async.getAsyncResult(2000);
        String content = async.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(content).contains("event:error");
        assertThat(content).contains("boom");
    }
}
