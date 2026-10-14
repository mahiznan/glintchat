package com.glint.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record StoredMessage(UUID messageId, UUID conversationId, long seq, String senderId,
                            UUID clientMsgId, String type, String body, Instant createdAt) {}
