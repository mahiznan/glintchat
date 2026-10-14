package com.glint.chat;

import com.glint.chat.ids.IdGenerator;
import com.glint.chat.ids.UuidBytes;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TestData {
    private TestData() {}

    /** One conversation with a customer and the given RMs. */
    public static UUID conversation(JdbcTemplate jdbc, IdGenerator ids, String customer, String... rms) {
        UUID id = ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO conversation (id, customer_id, last_seq, created_at) VALUES (?,?,0,?)",
                UuidBytes.toBytes(id), customer, now);
        jdbc.update("INSERT INTO conversation_participant (conversation_id, user_id, role, joined_at) VALUES (?,?,'CUSTOMER',?)",
                UuidBytes.toBytes(id), customer, now);
        for (String rm : rms) {
            jdbc.update("INSERT INTO conversation_participant (conversation_id, user_id, role, joined_at) VALUES (?,?,'RM',?)",
                    UuidBytes.toBytes(id), rm, now);
        }
        return id;
    }
}
