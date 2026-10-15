package com.glint.gateway.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The MFE-facing history endpoint of the gateway, with chat-service mocked. */
class HistoryControllerTest {

    private ChatServiceClient chat;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        chat = mock(ChatServiceClient.class);
        mvc = MockMvcBuilders.standaloneSetup(new HistoryController(chat)).build();
    }

    @Test
    void forwardsTheUserAndParametersAndReturnsTheBody() throws Exception {
        when(chat.history("c-1", "customer-1", 7, 25))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"messages\":[]}"));

        mvc.perform(get("/conversations/c-1/messages?afterSeq=7&limit=25").header("X-User-Id", "customer-1"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"messages\":[]}"));
        verify(chat).history("c-1", "customer-1", 7, 25);
    }

    @Test
    void usesTheDefaultsWhenTheParametersAreMissing() throws Exception {
        when(chat.history("c-1", "customer-1", 0, 50)).thenReturn(ResponseEntity.ok("{}"));

        mvc.perform(get("/conversations/c-1/messages").header("X-User-Id", "customer-1")).andExpect(status().isOk());
        verify(chat).history("c-1", "customer-1", 0, 50);
    }

    @Test
    void passesAForbiddenStatusFromChatServiceToTheClient() throws Exception {
        when(chat.history("c-1", "stranger", 0, 50))
                .thenReturn(ResponseEntity.status(HttpStatus.FORBIDDEN).body("{\"error\":\"not a participant\"}"));

        mvc.perform(get("/conversations/c-1/messages").header("X-User-Id", "stranger"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsARequestWithoutAUserId() throws Exception {
        mvc.perform(get("/conversations/c-1/messages")).andExpect(status().isBadRequest());
    }
}
