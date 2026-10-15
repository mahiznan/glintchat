package com.glint.chat.web;

import com.glint.chat.domain.SendCommand;
import com.glint.chat.domain.SendResult;
import com.glint.chat.domain.StoredMessage;
import com.glint.chat.service.HistoryService;
import com.glint.chat.service.MessageService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MessageController {
    public record SendRequest(String userId, String originSessionId, UUID clientMsgId, String type, String body) {}
    public record SendResponse(UUID messageId, long seq, UUID clientMsgId, boolean duplicate) {}
    public record MessageDto(UUID messageId, long seq, String senderId, UUID clientMsgId, String type, String body, String createdAt) {}
    public record HistoryResponse(List<MessageDto> messages) {}

    private final MessageService messageService;
    private final HistoryService historyService;

    public MessageController(MessageService messageService, HistoryService historyService) {
        this.messageService = messageService; this.historyService = historyService;
    }

    /** Internal: called by the gateway. DEVELOPMENT: userId comes from the body; real auth replaces this. */
    @PostMapping("/internal/conversations/{conversationId}/messages")
    public SendResponse send(@PathVariable UUID conversationId, @RequestBody SendRequest r) {
        if (r.userId() == null || r.userId().isBlank() || r.clientMsgId() == null
                || r.body() == null || r.body().isBlank()) {
            throw new IllegalArgumentException("userId, clientMsgId and a non-blank body are required");
        }
        String type = (r.type() == null || r.type().isBlank()) ? "text" : r.type();
        SendResult res = messageService.send(
                new SendCommand(conversationId, r.userId(), r.originSessionId(), r.clientMsgId(), type, r.body()));
        return new SendResponse(res.messageId(), res.seq(), res.clientMsgId(), res.duplicate());
    }

    /**
     * Internal: called by the gateway on behalf of the MFE, which never calls chat-service (D18, 2026-10-15).
     * DEVELOPMENT: the end user is identified by the X-User-Id header; a service credential and a verified user
     * identity replace this.
     */
    @GetMapping("/internal/conversations/{conversationId}/messages")
    public HistoryResponse history(@PathVariable UUID conversationId,
                                   @RequestHeader("X-User-Id") String userId,
                                   @RequestParam(defaultValue = "0") long afterSeq,
                                   @RequestParam(defaultValue = "50") int limit) {
        List<StoredMessage> list = historyService.history(conversationId, userId, afterSeq, limit);
        return new HistoryResponse(list.stream().map(m -> new MessageDto(m.messageId(), m.seq(), m.senderId(),
                m.clientMsgId(), m.type(), m.body(), m.createdAt().toString())).toList());
    }
}
