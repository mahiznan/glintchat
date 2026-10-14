package com.glint.chat.domain;

public class NotParticipantException extends RuntimeException {
    public NotParticipantException(String message) { super(message); }
}
