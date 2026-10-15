package com.glint.gateway.chat;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The MFE's only door to history: clients never call chat-service (decision of 2026-10-15, D18).
 * DEVELOPMENT: the caller is identified by the X-User-Id header; a verified token replaces this, and the
 * gateway-to-chat-service call gets a service credential (JWT), both out of scope for the slice.
 */
@RestController
public class HistoryController {
    private final ChatServiceClient chatService;

    public HistoryController(ChatServiceClient chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<String> history(@PathVariable String conversationId,
                                          @RequestHeader("X-User-Id") String userId,
                                          @RequestParam(defaultValue = "0") long afterSeq,
                                          @RequestParam(defaultValue = "50") int limit) {
        return chatService.history(conversationId, userId, afterSeq, limit);
    }
}
