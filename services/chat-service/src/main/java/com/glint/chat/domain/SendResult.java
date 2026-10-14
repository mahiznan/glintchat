package com.glint.chat.domain;

import java.util.UUID;

public record SendResult(UUID messageId, long seq, UUID clientMsgId, boolean duplicate) {}
