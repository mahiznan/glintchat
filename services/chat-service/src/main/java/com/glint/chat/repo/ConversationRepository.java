package com.glint.chat.repo;

import com.glint.chat.ids.UuidBytes;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Step 1 of the insert transaction: lock the row and read last_seq. Concurrent senders wait here. */
    public Optional<Long> lockAndGetLastSeq(UUID id) {
        return jdbc.query("SELECT last_seq FROM conversation WHERE id = ? FOR UPDATE",
                (rs, i) -> rs.getLong(1), UuidBytes.toBytes(id)).stream().findFirst();
    }

    public void updateLastSeq(UUID id, long seq) {
        jdbc.update("UPDATE conversation SET last_seq = ? WHERE id = ?", seq, UuidBytes.toBytes(id));
    }

    public boolean isActiveParticipant(UUID conversationId, String userId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM conversation_participant WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
            Integer.class, UuidBytes.toBytes(conversationId), userId);
        return n != null && n > 0;
    }

    public List<String> activeParticipantIds(UUID conversationId) {
        return jdbc.queryForList(
            "SELECT user_id FROM conversation_participant WHERE conversation_id = ? AND left_at IS NULL ORDER BY role, user_id",
            String.class, UuidBytes.toBytes(conversationId));
    }

    public Optional<Long> visibleFromSeq(UUID conversationId, String userId) {
        return jdbc.query(
            "SELECT visible_from_seq FROM conversation_participant WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
            (rs, i) -> rs.getLong(1), UuidBytes.toBytes(conversationId), userId).stream().findFirst();
    }
}
