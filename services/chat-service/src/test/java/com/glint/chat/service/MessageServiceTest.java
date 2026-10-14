package com.glint.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glint.chat.IntegrationTestBase;
import com.glint.chat.TestData;
import com.glint.chat.domain.NotParticipantException;
import com.glint.chat.domain.SendCommand;
import com.glint.chat.domain.SendResult;
import com.glint.chat.ids.IdGenerator;
import com.glint.chat.ids.UuidBytes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class MessageServiceTest extends IntegrationTestBase {

    @Autowired MessageService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdGenerator ids;
    @Autowired TransactionTemplate tx;

    private SendCommand cmd(UUID conv, String user, UUID clientMsgId, String body) {
        return new SendCommand(conv, user, UUID.randomUUID().toString(), clientMsgId, "text", body);
    }

    private int count(String table, UUID conv) {
        String col = table.equals("conversation") ? "id" : "conversation_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + col + " = ?",
                Integer.class, UuidBytes.toBytes(conv));
    }

    @Test
    void sendAssignsSeqOneAndWritesAnOutboxRow() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        SendResult r = service.send(cmd(conv, "c1", UUID.randomUUID(), "hello"));
        assertThat(r.seq()).isEqualTo(1);
        assertThat(r.duplicate()).isFalse();
        assertThat(count("message", conv)).isEqualTo(1);
        String payload = jdbc.queryForObject(
                "SELECT payload FROM outbox WHERE conversation_id = ?", String.class, UuidBytes.toBytes(conv));
        assertThat(payload).contains(r.messageId().toString()).contains("\"participants\"").contains("hello");
    }

    @Test
    void retryWithTheSameClientMsgIdReturnsTheOriginalResult() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        UUID clientMsgId = UUID.randomUUID();
        SendResult first = service.send(cmd(conv, "c1", clientMsgId, "hello"));
        SendResult retry = service.send(cmd(conv, "c1", clientMsgId, "hello"));
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.messageId()).isEqualTo(first.messageId());
        assertThat(retry.seq()).isEqualTo(first.seq());
        assertThat(count("message", conv)).isEqualTo(1);
        assertThat(count("outbox", conv)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesCreateExactlyOneMessage() throws Exception {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        UUID clientMsgId = UUID.randomUUID();
        List<SendResult> results = runConcurrently(10, i -> service.send(cmd(conv, "c1", clientMsgId, "same")));
        Set<UUID> messageIds = new HashSet<>();
        results.forEach(r -> messageIds.add(r.messageId()));
        assertThat(messageIds).hasSize(1);
        assertThat(count("message", conv)).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.duplicate()).count()).isEqualTo(1);
    }

    @Test
    void concurrentSendsGetGapFreeSequenceNumbers() throws Exception {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        List<SendResult> results = runConcurrently(20, i -> service.send(cmd(conv, "c1", UUID.randomUUID(), "m" + i)));
        Set<Long> seqs = new HashSet<>();
        results.forEach(r -> seqs.add(r.seq()));
        Set<Long> expected = new HashSet<>();
        for (long s = 1; s <= 20; s++) expected.add(s);
        assertThat(seqs).isEqualTo(expected);
    }

    @Test
    void aNonParticipantIsRejectedAndNothingIsWritten() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        assertThatThrownBy(() -> service.send(cmd(conv, "stranger", UUID.randomUUID(), "hi")))
                .isInstanceOf(NotParticipantException.class);
        assertThat(count("message", conv)).isZero();
        assertThat(count("outbox", conv)).isZero();
    }

    @Test
    void aRollbackLeavesNoMessageAndNoOutboxRow() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.send(cmd(conv, "c1", UUID.randomUUID(), "doomed"));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("message", conv)).isZero();
        assertThat(count("outbox", conv)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_seq FROM conversation WHERE id = ?",
                Long.class, UuidBytes.toBytes(conv))).isZero();
    }

    private <T> List<T> runConcurrently(int n, java.util.function.IntFunction<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) { final int idx = i; tasks.add(() -> work.apply(idx)); }
            List<T> out = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(tasks)) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }
}
