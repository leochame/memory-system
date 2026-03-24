package com.memsys.im.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.memsys.im.model.IncomingImMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuInboundParserTest {

    private final FeishuInboundParser parser = new FeishuInboundParser(new ObjectMapper());

    @Test
    void shouldParseTextMessageEvent() {
        Map<String, Object> payload = Map.of(
                "schema", "2.0",
                "header", Map.of(
                        "event_type", "im.message.receive_v1"
                ),
                "event", Map.of(
                        "message", Map.of(
                                "message_id", "om_123",
                                "chat_id", "oc_123456",
                                "message_type", "text",
                                "content", "{\"text\":\"hello feishu\"}",
                                "create_time", "1710000000"
                        ),
                        "sender", Map.of(
                                "sender_id", Map.of(
                                        "open_id", "ou_abc"
                                )
                        )
                )
        );

        Optional<IncomingImMessage> parsed = parser.parseMessageEvent(payload);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().platform()).isEqualTo("feishu");
        assertThat(parsed.get().conversationId()).isEqualTo("oc_123456");
        assertThat(parsed.get().senderId()).isEqualTo("ou_abc");
        assertThat(parsed.get().text()).isEqualTo("hello feishu");
        assertThat(parsed.get().messageId()).isEqualTo("om_123");
        assertThat(parsed.get().timestampMs()).isEqualTo(1710000000L * 1000L);
    }

    @Test
    void shouldIgnoreNonTextMessageEvent() {
        Map<String, Object> payload = Map.of(
                "header", Map.of(
                        "event_type", "im.message.receive_v1"
                ),
                "event", Map.of(
                        "message", Map.of(
                                "chat_id", "oc_123456",
                                "message_type", "image",
                                "content", "{}"
                        )
                )
        );

        Optional<IncomingImMessage> parsed = parser.parseMessageEvent(payload);
        assertThat(parsed).isEmpty();
    }

    @Test
    void shouldParsePostMessageEvent() {
        Map<String, Object> payload = Map.of(
                "header", Map.of(
                        "event_type", "im.message.receive_v1"
                ),
                "event", Map.of(
                        "message", Map.of(
                                "message_id", "om_post",
                                "chat_id", "oc_post",
                                "message_type", "post",
                                "content", "{\"zh_cn\":{\"content\":[[{\"tag\":\"text\",\"text\":\"hello \"},{\"tag\":\"text\",\"text\":\"world\"}]]}}"
                        ),
                        "sender", Map.of(
                                "sender_id", Map.of(
                                        "open_id", "ou_post"
                                )
                        )
                )
        );

        Optional<IncomingImMessage> parsed = parser.parseMessageEvent(payload);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().conversationId()).isEqualTo("oc_post");
        assertThat(parsed.get().senderId()).isEqualTo("ou_post");
        assertThat(parsed.get().text()).isEqualTo("hello world");
    }

    @Test
    void shouldParseLegacyMessageEvent() {
        Map<String, Object> legacyEvent = Map.of(
                "open_chat_id", "oc_legacy",
                "open_id", "ou_legacy",
                "msg_type", "text",
                "open_message_id", "om_legacy",
                "text_without_at_bot", "legacy message"
        );

        Optional<IncomingImMessage> parsed = parser.parseLegacyMessageEvent(legacyEvent);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().platform()).isEqualTo("feishu");
        assertThat(parsed.get().conversationId()).isEqualTo("oc_legacy");
        assertThat(parsed.get().senderId()).isEqualTo("ou_legacy");
        assertThat(parsed.get().text()).isEqualTo("legacy message");
        assertThat(parsed.get().messageId()).isEqualTo("om_legacy");
        assertThat(parsed.get().timestampMs()).isPositive();
    }
}
