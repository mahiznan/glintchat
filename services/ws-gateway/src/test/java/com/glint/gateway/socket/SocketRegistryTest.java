package com.glint.gateway.socket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SocketRegistryTest {

    private static ClientSocket socket(String session, String user) {
        return new ClientSocket() {
            public String sessionId() { return session; }
            public String userId() { return user; }
            public void send(String json) {}
        };
    }

    @Test
    void countsSocketsAndUsers() {
        SocketRegistry registry = new SocketRegistry();
        ClientSocket a1 = socket("s1", "a");
        ClientSocket a2 = socket("s2", "a");
        ClientSocket b1 = socket("s3", "b");
        registry.register(a1); registry.register(a2); registry.register(b1);
        assertThat(registry.socketCount()).isEqualTo(3);
        assertThat(registry.userCount()).isEqualTo(2);

        registry.unregister(a1); registry.unregister(a2); registry.unregister(b1);
        assertThat(registry.socketCount()).isZero();
        assertThat(registry.userCount()).isZero(); // empty sets are removed, so the map cannot grow forever
    }
}
