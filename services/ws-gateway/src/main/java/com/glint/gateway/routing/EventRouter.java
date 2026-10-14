package com.glint.gateway.routing;

import com.glint.gateway.socket.ClientSocket;
import com.glint.gateway.socket.SocketRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class EventRouter {
    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    public record MessageFrame(String type, String conversationId, GatewayEvent.Message message) {
    }

    private final SocketRegistry registry;
    private final ObjectMapper json;

    public EventRouter(SocketRegistry registry, ObjectMapper json) {
        this.registry = registry;
        this.json = json;
    }

    /**
     * Writes the event to every local socket of every participant, except the socket that sent it.
     */
    public int route(String payload) {
        GatewayEvent event;
        try {
            event = json.readValue(payload, GatewayEvent.class);
        } catch (RuntimeException e) {
            log.warn("dropping unreadable event: {}", e.getMessage());
            return 0;
        }
        if (!"message.created".equals(event.eventType()) || event.participants() == null) return 0;

        String frame = json.writeValueAsString(new MessageFrame("message", event.conversationId(), event.message()));
        int written = 0;
        for (String userId : event.participants()) {
            for (ClientSocket socket : registry.socketsOf(userId)) {
                if (socket.sessionId().equals(event.originSessionId())) continue; // it already has the ACK
                try {
                    socket.send(frame);
                    written++;
                } catch (RuntimeException e) {
                    log.warn("send to session {} failed: {}", socket.sessionId(), e.getMessage());
                }
            }
        }
        return written;
    }
}
