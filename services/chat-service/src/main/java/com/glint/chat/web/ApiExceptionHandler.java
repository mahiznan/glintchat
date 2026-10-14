package com.glint.chat.web;

import com.glint.chat.domain.ConversationNotFoundException;
import com.glint.chat.domain.NotParticipantException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ConversationNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ConversationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "conversation_not_found"));
    }

    @ExceptionHandler(NotParticipantException.class)
    ResponseEntity<Map<String, String>> forbidden(NotParticipantException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "not_a_participant"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_request"));
    }
}
