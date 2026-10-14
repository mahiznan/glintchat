package com.glint.chat.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.glint.chat.IntegrationTestBase;
import com.glint.chat.TestData;
import com.glint.chat.domain.SendCommand;
import com.glint.chat.domain.SendResult;
import com.glint.chat.ids.IdGenerator;
import com.glint.chat.ids.UuidBytes;
import com.glint.chat.repo.OutboxRow;
import com.glint.chat.service.MessageService;
import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class OutboxRelayTest extends IntegrationTestBase {

    @Autowired OutboxRelay relay;
    @Autowired MessageService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdGenerator ids;
    @MockitoSpyBean EventPublisher publisher;

    private SendResult send(UUID conv) {
        return service.send(new SendCommand(conv, "c1", "session-1", UUID.randomUUID(), "text", "hello"));
    }

    private int unpublished(UUID conv) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE conversation_id = ? AND published_at IS NULL",
                Integer.class, UuidBytes.toBytes(conv));
    }

    @Test
    void publishesToTheTopicAndMarksTheRowPublished() throws Exception {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        // Drain anything left by other tests, then subscribe BEFORE publishing (a topic only reaches existing subscribers).
        relay.relayOnce();
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(ARTEMIS.getBrokerUrl(), ARTEMIS.getUser(), ARTEMIS.getPassword());
        try (Connection conn = cf.createConnection()) {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createTopic("chat.events"));

            SendResult sent = send(conv);
            assertThat(unpublished(conv)).isEqualTo(1);
            assertThat(relay.relayOnce()).isGreaterThanOrEqualTo(1);

            TextMessage received = (TextMessage) consumer.receive(5000);
            assertThat(received).isNotNull();
            assertThat(received.getStringProperty("eventType")).isEqualTo("message.created");
            assertThat(received.getStringProperty("conversationId")).isEqualTo(conv.toString());
            assertThat(received.getText()).contains(sent.messageId().toString());
            assertThat(unpublished(conv)).isZero();
            assertThat(relay.relayOnce()).isZero();
        }
    }

    @Test
    void aFailedPublishLeavesTheRowForTheNextAttempt() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        relay.relayOnce();
        send(conv);
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(any(OutboxRow.class));
        assertThatThrownBy(() -> relay.relayOnce()).isInstanceOf(IllegalStateException.class);
        assertThat(unpublished(conv)).isEqualTo(1);

        Mockito.reset(publisher); // the broker is back
        assertThat(relay.relayOnce()).isGreaterThanOrEqualTo(1);
        assertThat(unpublished(conv)).isZero();
    }
}
