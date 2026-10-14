package com.glint.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.glint.gateway.socket.ClientSocket;
import com.glint.gateway.socket.SocketRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventRouterTest {

    static class FakeSocket implements ClientSocket {
        final String sessionId, userId;
        final List<String> sent = new ArrayList<>();
        FakeSocket(String sessionId, String userId) { this.sessionId = sessionId; this.userId = userId; }
        public String sessionId() { return sessionId; }
        public String userId() { return userId; }
        public void send(String json) { sent.add(json); }
    }

    private final SocketRegistry registry = new SocketRegistry();
    private final EventRouter router = new EventRouter(registry, JsonMapper.builder().build());

    private String event(String origin, String... participants) {
        String list = String.join("\",\"", participants);
        return "{\"schemaVersion\":1,\"eventId\":\"e1\",\"eventType\":\"message.created\",\"occurredAt\":\"t\","
             + "\"traceId\":\"tr\",\"conversationId\":\"conv-1\",\"participants\":[\"" + list + "\"],"
             + "\"originSessionId\":" + (origin == null ? "null" : "\"" + origin + "\"") + ","
             + "\"message\":{\"messageId\":\"m1\",\"seq\":1,\"senderId\":\"c1\",\"clientMsgId\":\"x\","
             + "\"type\":\"text\",\"body\":\"hi\",\"createdAt\":\"t\"},\"futureField\":true}";
    }

    @Test
    void deliversToParticipantsAndSkipsTheOriginSocket() {
        FakeSocket origin = new FakeSocket("s1", "c1");
        FakeSocket otherTab = new FakeSocket("s2", "c1");
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(origin); registry.register(otherTab); registry.register(rm);

        int written = router.route(event("s1", "c1", "r1"));

        assertThat(written).isEqualTo(2);
        assertThat(origin.sent).isEmpty();
        assertThat(otherTab.sent).hasSize(1);
        assertThat(rm.sent).hasSize(1);
        assertThat(rm.sent.get(0)).contains("\"type\":\"message\"").contains("\"body\":\"hi\"").contains("conv-1");
    }

    @Test
    void ignoresParticipantsWithoutASocketOnThisPod() {
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(rm);
        assertThat(router.route(event(null, "c1", "r1"))).isEqualTo(1); // c1 is connected to another pod
    }

    @Test
    void ignoresMalformedAndUnknownEvents() {
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(rm);
        assertThat(router.route("not json")).isZero();
        assertThat(router.route(event(null, "r1").replace("message.created", "something.else"))).isZero();
        assertThat(rm.sent).isEmpty();
    }

    @Test
    void aFailingSocketDoesNotStopTheOthers() {
        ClientSocket broken = new FakeSocket("s1", "c1") {
            @Override public void send(String json) { throw new IllegalStateException("closed"); }
        };
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(broken); registry.register(rm);
        assertThat(router.route(event(null, "c1", "r1"))).isEqualTo(1);
        assertThat(rm.sent).hasSize(1);
    }

    @Test
    void unregisterRemovesTheSocket() {
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(rm);
        registry.unregister(rm);
        assertThat(registry.socketsOf("r1")).isEmpty();
    }
}
