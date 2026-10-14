package com.glint.chat.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    private final IdGenerator ids = new UuidV7Generator();

    @Test
    void generatesVersion7() {
        assertThat(ids.newId().version()).isEqualTo(7);
    }

    @Test
    void generatesUniqueIds() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) seen.add(ids.newId());
        assertThat(seen).hasSize(10_000);
    }

    @Test
    void laterIdsSortAfterEarlierOnes() throws Exception {
        UUID first = ids.newId();
        Thread.sleep(3);
        UUID second = ids.newId();
        assertThat(java.util.Arrays.compareUnsigned(UuidBytes.toBytes(first), UuidBytes.toBytes(second))).isNegative();
    }
}
