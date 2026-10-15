package com.glint.gateway.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.glint.gateway.GatewayCorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** CORS is decided at the gateway edge: only the configured web origin may read history from a browser. */
@WebMvcTest(HistoryController.class)
@Import(GatewayCorsConfig.class)
@TestPropertySource(properties = "gateway.cors.allowed-origin=http://localhost:8000")
class GatewayCorsTest {

    @Autowired MockMvc mvc;
    @MockitoBean ChatServiceClient chatService;

    @Test
    void theConfiguredOriginMayCallHistoryWithTheUserHeader() throws Exception {
        mvc.perform(options("/conversations/c-1/messages")
                        .header("Origin", "http://localhost:8000")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-user-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8000"));
    }

    @Test
    void anotherOriginIsRefused() throws Exception {
        mvc.perform(options("/conversations/c-1/messages")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-user-id"))
                .andExpect(status().isForbidden());
    }
}
