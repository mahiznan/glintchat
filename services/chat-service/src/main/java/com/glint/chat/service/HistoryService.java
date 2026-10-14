package com.glint.chat.service;

import com.glint.chat.domain.NotParticipantException;
import com.glint.chat.domain.StoredMessage;
import com.glint.chat.repo.ConversationRepository;
import com.glint.chat.repo.MessageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class HistoryService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;

    public HistoryService(ConversationRepository conversations, MessageRepository messages) {
        this.conversations = conversations; this.messages = messages;
    }

    /** The server, not the client, applies the participant's visible_from_seq. */
    public List<StoredMessage> history(UUID conversationId, String userId, long afterSeq, int limit) {
        long visibleFrom = conversations.visibleFromSeq(conversationId, userId)
                .orElseThrow(() -> new NotParticipantException(userId + " is not a participant of " + conversationId));
        return messages.findAfterSeq(conversationId, afterSeq, visibleFrom, Math.max(1, Math.min(limit, 200)));
    }
}
