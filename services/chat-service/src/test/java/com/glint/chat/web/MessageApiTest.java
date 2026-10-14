package com.glint.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glint.chat.IntegrationTestBase;
import com.glint.chat.TestData;
import com.glint.chat.ids.IdGenerator;
import com.glint.chat.ids.UuidBytes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "chat.relay.enabled=false")
class MessageApiTest extends IntegrationTestBase {

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdGenerator ids;

    private RestClient http() { return RestClient.create("http://localhost:" + port); }

    private Map<?, ?> send(UUID conv, String user, UUID clientMsgId, String body) {
        return http().post().uri("/internal/conversations/{id}/messages", conv)
                .body(Map.of("userId", user, "originSessionId", "s1", "clientMsgId", clientMsgId.toString(),
                        "type", "text", "body", body))
                .retrieve().body(Map.class);
    }

    private Map<?, ?> history(UUID conv, String user, long afterSeq) {
        return http().get().uri("/conversations/{id}/messages?afterSeq={a}", conv, afterSeq)
                .header("X-User-Id", user).retrieve().body(Map.class);
    }

    @Test
    void sendThenReadHistory() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        Map<?, ?> ack = send(conv, "c1", UUID.randomUUID(), "hello");
        assertThat(ack.get("seq")).isEqualTo(1);
        assertThat(ack.get("duplicate")).isEqualTo(false);

        List<?> messages = (List<?>) history(conv, "r1", 0).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(((Map<?, ?>) messages.get(0)).get("body")).isEqualTo("hello");
    }

    @Test
    void afterSeqSkipsEarlierMessages() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        send(conv, "c1", UUID.randomUUID(), "one");
        send(conv, "r1", UUID.randomUUID(), "two");
        List<?> messages = (List<?>) history(conv, "c1", 1).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(((Map<?, ?>) messages.get(0)).get("seq")).isEqualTo(2);
    }

    @Test
    void aStrangerGets403OnSendAndOnHistory() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        assertThatThrownBy(() -> send(conv, "stranger", UUID.randomUUID(), "x"))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> history(conv, "stranger", 0))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void anUnknownConversationGets404() {
        assertThatThrownBy(() -> send(UUID.randomUUID(), "c1", UUID.randomUUID(), "x"))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void historyHidesMessagesBeforeVisibleFromSeq() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        send(conv, "c1", UUID.randomUUID(), "before-join");
        send(conv, "c1", UUID.randomUUID(), "after-join");
        jdbc.update("UPDATE conversation_participant SET visible_from_seq = 2 WHERE conversation_id = ? AND user_id = 'r1'",
                UuidBytes.toBytes(conv));
        List<?> messages = (List<?>) history(conv, "r1", 0).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(((Map<?, ?>) messages.get(0)).get("body")).isEqualTo("after-join");
    }

    @Test
    void aBlankBodyGets400() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        assertThatThrownBy(() -> send(conv, "c1", UUID.randomUUID(), " "))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }
}
