package com.glint.chat.repo;

import com.glint.chat.ids.UuidBytes;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(UUID eventId, String eventType, UUID conversationId, String payload, String traceId, Instant now) {
        jdbc.update("INSERT INTO outbox (event_id, event_type, conversation_id, payload, trace_id, created_at) VALUES (?,?,?,?,?,?)",
                UuidBytes.toBytes(eventId), eventType, UuidBytes.toBytes(conversationId), payload, traceId, Timestamp.from(now));
    }

    /** Oldest unpublished rows, locked for this transaction; rows locked by another relay are skipped. */
    public List<OutboxRow> lockUnpublishedBatch(int limit) {
        return jdbc.query("SELECT id, event_id, event_type, conversation_id, payload FROM outbox "
                        + "WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new OutboxRow(rs.getLong("id"), UuidBytes.fromBytes(rs.getBytes("event_id")),
                        rs.getString("event_type"), UuidBytes.fromBytes(rs.getBytes("conversation_id")),
                        rs.getString("payload")), limit);
    }

    public void markPublished(long id, Instant now) {
        jdbc.update("UPDATE outbox SET published_at = ? WHERE id = ?", Timestamp.from(now), id);
    }
}
