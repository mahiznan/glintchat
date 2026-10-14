package com.glint.gateway.ws;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.glint.gateway.chat.ChatServiceClient;
import com.glint.gateway.chat.SendResponse;
import com.glint.gateway.socket.ClientSocket;
import com.glint.gateway.socket.SocketRegistry;
import com.glint.gateway.socket.WebSocketClientSocket;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String SOCKET = "socket";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Inbound(String type, String conversationId, String clientMsgId, String body) {}

    private final SocketRegistry registry;
    private final ChatServiceClient chatService;
    private final ObjectMapper json;

    public ChatWebSocketHandler(SocketRegistry registry, ChatServiceClient chatService, ObjectMapper json) {
        this.registry = registry; this.chatService = chatService; this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // DEVELOPMENT STUB: the user id comes from the query string. Real authentication replaces this.
        String userId = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("userId");
        if (userId == null || userId.isBlank()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("userId required"));
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        ClientSocket socket = new WebSocketClientSocket(
                new ConcurrentWebSocketSessionDecorator(session, 5_000, 64 * 1024), sessionId, userId);
        session.getAttributes().put(SOCKET, socket);
        registry.register(socket);
        socket.send(json.writeValueAsString(Map.of("type", "ready", "sessionId", sessionId)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientSocket socket = (ClientSocket) session.getAttributes().get(SOCKET);
        if (socket == null) return;
        Inbound in;
        try {
            in = json.readValue(message.getPayload(), Inbound.class);
        } catch (RuntimeException e) {
            socket.send(error(null, "bad_frame"));
            return;
        }
        if (!"send".equals(in.type()) || in.conversationId() == null || in.clientMsgId() == null || in.body() == null) {
            socket.send(error(in.clientMsgId(), "bad_frame"));
            return;
        }
        try {
            SendResponse r = chatService.send(in.conversationId(), socket.userId(), socket.sessionId(),
                    in.clientMsgId(), in.body());
            socket.send(json.writeValueAsString(Map.of("type", "ack", "clientMsgId", in.clientMsgId(),
                    "messageId", r.messageId(), "seq", r.seq(), "duplicate", r.duplicate())));
        } catch (RuntimeException e) {
            log.warn("send failed for {}: {}", socket.userId(), e.getMessage());
            socket.send(error(in.clientMsgId(), "send_failed"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object socket = session.getAttributes().get(SOCKET);
        if (socket instanceof ClientSocket s) registry.unregister(s);
    }

    private String error(String clientMsgId, String code) {
        Map<String, Object> frame = new java.util.HashMap<>();
        frame.put("type", "error");
        frame.put("clientMsgId", clientMsgId);
        frame.put("code", code);
        return json.writeValueAsString(frame);
    }
}
