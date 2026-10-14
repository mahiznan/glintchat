package com.glint.chat.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidBytesTest {
    @Test
    void roundTrips() {
        UUID id = UUID.randomUUID();
        byte[] bytes = UuidBytes.toBytes(id);
        assertThat(bytes).hasSize(16);
        assertThat(UuidBytes.fromBytes(bytes)).isEqualTo(id);
    }
}
