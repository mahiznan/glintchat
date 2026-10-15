package com.glint.gateway.chat;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    /**
     * History for the MFE. The status and body of chat-service are passed through unchanged, so a 403 for a
     * stranger stays a 403 (DEVELOPMENT: error bodies are not filtered; see the D18 shortcut list).
     */
    public ResponseEntity<String> history(String conversationId, String userId, long afterSeq, int limit) {
        return http.get()
                .uri("/internal/conversations/{id}/messages?afterSeq={a}&limit={l}", conversationId, afterSeq, limit)
                .header("X-User-Id", userId)
                .exchange((request, response) -> {
                    ResponseEntity.BodyBuilder out = ResponseEntity.status(response.getStatusCode());
                    MediaType type = response.getHeaders().getContentType();
                    if (type != null) out.contentType(type);
                    return out.body(response.bodyTo(String.class));
                });
    }
}
