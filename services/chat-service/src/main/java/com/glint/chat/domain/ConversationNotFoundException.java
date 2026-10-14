package com.glint.chat.domain;

public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException(String message) { super(message); }
}
