package com.glint.gateway.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** The consumer's view of the envelope on chat.events. Unknown fields are ignored (rolling deploys). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayEvent(int schemaVersion, String eventId, String eventType, String conversationId,
                           List<String> participants, String originSessionId, Message message) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String messageId, long seq, String senderId, String clientMsgId,
                          String type, String body, String createdAt) {}
}
