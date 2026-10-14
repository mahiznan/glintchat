package com.glint.chat.repo;

import com.glint.chat.domain.StoredMessage;
import com.glint.chat.ids.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
    private static final RowMapper<StoredMessage> MAPPER = MessageRepository::map;
    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(StoredMessage m) {
        jdbc.update("INSERT INTO message (id, conversation_id, seq, sender_id, client_msg_id, type, body, created_at) "
                  + "VALUES (?,?,?,?,?,?,?,?)",
                UuidBytes.toBytes(m.messageId()), UuidBytes.toBytes(m.conversationId()), m.seq(), m.senderId(),
                UuidBytes.toBytes(m.clientMsgId()), m.type(), m.body(), Timestamp.from(m.createdAt()));
    }

    public Optional<StoredMessage> findByIdempotencyKey(UUID conversationId, String senderId, UUID clientMsgId) {
        return jdbc.query("SELECT * FROM message WHERE conversation_id = ? AND sender_id = ? AND client_msg_id = ?",
                MAPPER, UuidBytes.toBytes(conversationId), senderId, UuidBytes.toBytes(clientMsgId)).stream().findFirst();
    }

    /** Messages after {@code afterSeq} that the participant may see ({@code seq >= visibleFromSeq}), ascending. */
    public List<StoredMessage> findAfterSeq(UUID conversationId, long afterSeq, long visibleFromSeq, int limit) {
        return jdbc.query("SELECT * FROM message WHERE conversation_id = ? AND seq > ? AND seq >= ? ORDER BY seq LIMIT ?",
                MAPPER, UuidBytes.toBytes(conversationId), afterSeq, visibleFromSeq, limit);
    }

    private static StoredMessage map(ResultSet rs, int i) throws SQLException {
        return new StoredMessage(UuidBytes.fromBytes(rs.getBytes("id")), UuidBytes.fromBytes(rs.getBytes("conversation_id")),
                rs.getLong("seq"), rs.getString("sender_id"), UuidBytes.fromBytes(rs.getBytes("client_msg_id")),
                rs.getString("type"), rs.getString("body"), rs.getTimestamp("created_at").toInstant());
    }
}
