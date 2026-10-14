package com.glint.chat.event;

public record EventMessage(String messageId, long seq, String senderId, String clientMsgId,
                           String type, String body, String createdAt) {}
