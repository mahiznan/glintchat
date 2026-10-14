package com.glint.gateway.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.glint.gateway.GatewayIntegrationTestBase;
import com.glint.gateway.chat.ChatServiceClient;
import com.glint.gateway.chat.SendResponse;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WebSocketFlowTest extends GatewayIntegrationTestBase {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JmsTemplate jms;
    @MockitoBean ChatServiceClient chatService;

    static class TestClient extends TextWebSocketHandler {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocketSession session;
        volatile CloseStatus closed;
        @Override public void afterConnectionEstablished(WebSocketSession s) { session = s; }
        @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) { frames.add(m.getPayload()); }
        @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus status) { closed = status; }
    }

    private TestClient connect(String userId) throws Exception {
        TestClient client = new TestClient();
        new StandardWebSocketClient().execute((WebSocketHandler) client, "ws://localhost:" + port + "/ws?userId=" + userId)
                .get(5, TimeUnit.SECONDS);
        return client;
    }

    private JsonNode next(TestClient c) throws Exception {
        String frame = c.frames.poll(5, TimeUnit.SECONDS);
        assertThat(frame).as("a frame within 5 s").isNotNull();
        return json.readTree(frame);
    }

    @Test
    void sendAcksTheOriginAndTheEventReachesTheOtherSocketsOnly() throws Exception {
        TestClient customer = connect("c1");
        TestClient customerTab2 = connect("c1");
        TestClient rm = connect("r1");
        String customerSession = next(customer).get("sessionId").asString();
        next(customerTab2); next(rm);

        when(chatService.send(eq("conv-1"), eq("c1"), eq(customerSession), eq("cm-1"), eq("hello")))
                .thenReturn(new SendResponse("m-1", 1, "cm-1", false));

        customer.session.sendMessage(new TextMessage(
                "{\"type\":\"send\",\"conversationId\":\"conv-1\",\"clientMsgId\":\"cm-1\",\"body\":\"hello\"}"));
        JsonNode ack = next(customer);
        assertThat(ack.get("type").asString()).isEqualTo("ack");
        assertThat(ack.get("messageId").asString()).isEqualTo("m-1");
        assertThat(ack.get("seq").asLong()).isEqualTo(1);

        // Simulate the relay: publish the envelope to the topic.
        JmsTemplate topic = new JmsTemplate(jms.getConnectionFactory());
        topic.setPubSubDomain(true);
        topic.convertAndSend("chat.events",
            "{\"schemaVersion\":1,\"eventId\":\"e1\",\"eventType\":\"message.created\",\"conversationId\":\"conv-1\","
          + "\"participants\":[\"c1\",\"r1\"],\"originSessionId\":\"" + customerSession + "\","
          + "\"message\":{\"messageId\":\"m-1\",\"seq\":1,\"senderId\":\"c1\",\"clientMsgId\":\"cm-1\","
          + "\"type\":\"text\",\"body\":\"hello\",\"createdAt\":\"t\"}}");

        assertThat(next(rm).get("type").asString()).isEqualTo("message");
        assertThat(next(customerTab2).get("message").get("body").asString()).isEqualTo("hello");
        assertThat(customer.frames.poll(1, TimeUnit.SECONDS)).as("the origin socket gets no duplicate").isNull();
    }

    @Test
    void aFailingSendBecomesAnErrorFrame() throws Exception {
        TestClient customer = connect("c2");
        next(customer);
        when(chatService.send(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("chat-service down"));
        customer.session.sendMessage(new TextMessage(
                "{\"type\":\"send\",\"conversationId\":\"conv-2\",\"clientMsgId\":\"cm-9\",\"body\":\"x\"}"));
        JsonNode error = next(customer);
        assertThat(error.get("type").asString()).isEqualTo("error");
        assertThat(error.get("clientMsgId").asString()).isEqualTo("cm-9");
    }

    @Test
    void aConnectionWithoutUserIdIsClosed() throws Exception {
        TestClient client = new TestClient();
        new StandardWebSocketClient().execute((WebSocketHandler) client, "ws://localhost:" + port + "/ws")
                .get(5, TimeUnit.SECONDS);
        long deadline = System.currentTimeMillis() + 5000;
        while (client.closed == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(client.closed).isNotNull();
    }
}
