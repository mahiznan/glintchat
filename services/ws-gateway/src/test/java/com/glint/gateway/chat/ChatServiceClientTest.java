package com.glint.gateway.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The history call against a stub chat-service: no Docker, no Spring context. */
class ChatServiceClientTest {

    private HttpServer server;
    private ChatServiceClient client;
    private final AtomicReference<String> seenPathAndQuery = new AtomicReference<>();
    private final AtomicReference<String> seenUser = new AtomicReference<>();

    @BeforeEach
    void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenPathAndQuery.set(exchange.getRequestURI().toString());
            seenUser.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            boolean stranger = "stranger".equals(seenUser.get());
            byte[] body = (stranger ? "{\"error\":\"not a participant\"}" : "{\"messages\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stranger ? 403 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new ChatServiceClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void historyCallsTheInternalPathWithTheUserAndTheParameters() {
        ResponseEntity<String> res = client.history("c-1", "customer-1", 7, 25);

        assertThat(seenPathAndQuery.get()).isEqualTo("/internal/conversations/c-1/messages?afterSeq=7&limit=25");
        assertThat(seenUser.get()).isEqualTo("customer-1");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isEqualTo("{\"messages\":[]}");
    }

    @Test
    void anErrorStatusFromChatServiceIsPassedThroughNotThrown() {
        ResponseEntity<String> res = client.history("c-1", "stranger", 0, 50);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).contains("not a participant");
    }
}
