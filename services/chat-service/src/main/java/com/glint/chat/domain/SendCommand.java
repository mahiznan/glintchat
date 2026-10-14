package com.glint.chat.domain;

import java.util.UUID;

public record SendCommand(UUID conversationId, String userId, String originSessionId,
                          UUID clientMsgId, String type, String body) {}
