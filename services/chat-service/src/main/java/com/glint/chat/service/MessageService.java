package com.glint.chat.service;

import com.glint.chat.domain.ConversationNotFoundException;
import com.glint.chat.domain.NotParticipantException;
import com.glint.chat.domain.SendCommand;
import com.glint.chat.domain.SendResult;
import com.glint.chat.domain.StoredMessage;
import com.glint.chat.event.EventMessage;
import com.glint.chat.event.MessageCreatedEvent;
import com.glint.chat.ids.IdGenerator;
import com.glint.chat.repo.ConversationRepository;
import com.glint.chat.repo.MessageRepository;
import com.glint.chat.repo.OutboxRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class MessageService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final OutboxRepository outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final ObjectMapper json;

    public MessageService(ConversationRepository conversations, MessageRepository messages, OutboxRepository outbox,
                          IdGenerator ids, Clock clock, ObjectMapper json) {
        this.conversations = conversations;
        this.messages = messages;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    /**
     * The persist-before-publish transaction. The order of the steps is the design (docs/chat-design.md section 2).
     */
    @Transactional
    public SendResult send(SendCommand c) {
        // 1. Lock the conversation row. Concurrent sends to the same conversation queue up here.
        long lastSeq = conversations.lockAndGetLastSeq(c.conversationId())
                .orElseThrow(() -> new ConversationNotFoundException("conversation " + c.conversationId()));
        // 2. Membership.
        if (!conversations.isActiveParticipant(c.conversationId(), c.userId())) {
            throw new NotParticipantException(c.userId() + " is not a participant of " + c.conversationId());
        }
        // 3. Idempotency: a retry returns the original result.
        Optional<StoredMessage> existing = messages.findByIdempotencyKey(c.conversationId(), c.userId(), c.clientMsgId());
        if (existing.isPresent()) {
            StoredMessage m = existing.get();
            return new SendResult(m.messageId(), m.seq(), m.clientMsgId(), true);
        }
        // 4. Assign seq and store the message.
        Instant now = clock.instant();
        long seq = lastSeq + 1;
        UUID messageId = ids.newId();
        conversations.updateLastSeq(c.conversationId(), seq);
        messages.insert(new StoredMessage(messageId, c.conversationId(), seq, c.userId(), c.clientMsgId(),
                c.type(), c.body(), now));
        // 5. The outbox row, in the same transaction, with the participants as they are right now.
        UUID eventId = ids.newId();
        List<String> participants = conversations.activeParticipantIds(c.conversationId());
        MessageCreatedEvent event = new MessageCreatedEvent(1, eventId.toString(), "message.created", now.toString(),
                UUID.randomUUID().toString(), c.conversationId().toString(), participants, c.originSessionId(),
                new EventMessage(messageId.toString(), seq, c.userId(), c.clientMsgId().toString(), c.type(),
                        c.body(), now.toString()));
        outbox.insert(eventId, "message.created", c.conversationId(), json.writeValueAsString(event),
                event.traceId(), now);
        return new SendResult(messageId, seq, c.clientMsgId(), false);
    }
}
