package com.glint.chat.repo;

import java.util.UUID;

public record OutboxRow(long id, UUID eventId, String eventType, UUID conversationId, String payload) {}
