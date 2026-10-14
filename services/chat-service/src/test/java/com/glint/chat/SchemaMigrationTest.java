package com.glint.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatesTheFourTables() {
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);
        assertThat(tables).contains("conversation", "conversation_participant", "message", "outbox");
    }

    @Test
    void messageHasTheTwoUniqueKeys() {
        List<String> keys = jdbc.queryForList(
            "SELECT DISTINCT index_name FROM information_schema.statistics "
          + "WHERE table_schema = DATABASE() AND table_name = 'message' AND non_unique = 0", String.class);
        assertThat(keys).contains("uq_conversation_seq", "uq_idempotency");
    }
}
