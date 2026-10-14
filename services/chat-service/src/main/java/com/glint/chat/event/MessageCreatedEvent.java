package com.glint.chat.event;

import java.util.List;

public record MessageCreatedEvent(int schemaVersion, String eventId, String eventType, String occurredAt,
                                  String traceId, String conversationId, List<String> participants,
                                  String originSessionId, EventMessage message) {}
