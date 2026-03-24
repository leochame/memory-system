package com.memsys.im.telegram;

import com.memsys.im.model.IncomingImMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramInboundParserTest {

    private final TelegramInboundParser parser = new TelegramInboundParser();

    @Test
    void shouldParseTextMessage() {
        Map<String, Object> payload = Map.of(
                "update_id", 12345,
                "message", Map.of(
                        "message_id", 321,
                        "date", 1710000000,
                        "text", "hello telegram",
                        "chat", Map.of("id", 99887766),
                        "from", Map.of("id", 11223344)
                )
        );

        Optional<IncomingImMessage> parsed = parser.parse(payload);
        assertThat(parsed).isPresent();
        assertThat(parsed.get().platform()).isEqualTo("telegram");
        assertThat(parsed.get().conversationId()).isEqualTo("99887766");
        assertThat(parsed.get().senderId()).isEqualTo("11223344");
        assertThat(parsed.get().text()).isEqualTo("hello telegram");
        assertThat(parsed.get().messageId()).isEqualTo("321");
        assertThat(parsed.get().timestampMs()).isEqualTo(1710000000L * 1000L);
    }

    @Test
    void shouldIgnoreNonTextMessage() {
        Map<String, Object> payload = Map.of(
                "message", Map.of(
                        "message_id", 321,
                        "chat", Map.of("id", 99887766),
                        "from", Map.of("id", 11223344)
                )
        );

        Optional<IncomingImMessage> parsed = parser.parse(payload);
        assertThat(parsed).isEmpty();
    }
}

