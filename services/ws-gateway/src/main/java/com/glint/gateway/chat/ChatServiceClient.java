package com.glint.gateway.chat;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The gateway never writes the database and never publishes: it asks chat-service to persist (D5). */
@Component
public class ChatServiceClient {
    private final RestClient http;

    public ChatServiceClient(@Value("${chat-service.base-url}") String baseUrl) {
        this.http = RestClient.create(baseUrl);
    }

    public SendResponse send(String conversationId, String userId, String originSessionId, String clientMsgId, String body) {
        return http.post().uri("/internal/conversations/{id}/messages", conversationId)
                .body(Map.of("userId", userId, "originSessionId", originSessionId,
                        "clientMsgId", clientMsgId, "type", "text", "body", body))
                .retrieve().body(SendResponse.class);
    }
}
