package com.glint.chat.ids;

import java.nio.ByteBuffer;
import java.util.UUID;

/** BINARY(16) in MariaDB, UUID in Java. Big-endian, so the byte order equals the UUID order. */
public final class UuidBytes {
    private UuidBytes() {}

    public static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    public static UUID fromBytes(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        return new UUID(b.getLong(), b.getLong());
    }
}
