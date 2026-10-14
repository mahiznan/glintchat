package com.glint.gateway.socket;

import java.io.IOException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

public class WebSocketClientSocket implements ClientSocket {
    private final WebSocketSession session; // a ConcurrentWebSocketSessionDecorator: safe for several sending threads
    private final String sessionId, userId;

    public WebSocketClientSocket(WebSocketSession session, String sessionId, String userId) {
        this.session = session; this.sessionId = sessionId; this.userId = userId;
    }

    @Override public String sessionId() { return sessionId; }
    @Override public String userId() { return userId; }

    @Override
    public void send(String json) {
        try {
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            throw new IllegalStateException("send failed for session " + sessionId, e);
        }
    }
}
