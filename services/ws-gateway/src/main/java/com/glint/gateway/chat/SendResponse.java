package com.glint.gateway.chat;

public record SendResponse(String messageId, long seq, String clientMsgId, boolean duplicate) {}
