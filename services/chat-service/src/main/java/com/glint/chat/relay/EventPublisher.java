package com.glint.chat.relay;

import com.glint.chat.repo.OutboxRow;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/** Publishes one outbox row to the multicast address chat.events. Non-persistent: per-pod queues are non-durable. */
@Component
public class EventPublisher {
    public static final String ADDRESS = "chat.events";
    private final JmsTemplate jms;

    public EventPublisher(ConnectionFactory connectionFactory) {
        this.jms = new JmsTemplate(connectionFactory);
        this.jms.setPubSubDomain(true);                       // topic = multicast
        this.jms.setExplicitQosEnabled(true);
        this.jms.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    }

    public void publish(OutboxRow row) {
        jms.send(ADDRESS, session -> {
            var message = session.createTextMessage(row.payload());
            message.setStringProperty("eventType", row.eventType());
            message.setStringProperty("conversationId", row.conversationId().toString());
            message.setStringProperty("eventId", row.eventId().toString());
            return message;
        });
    }
}
