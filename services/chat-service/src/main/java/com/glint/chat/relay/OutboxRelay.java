package com.glint.chat.relay;

import com.glint.chat.repo.OutboxRepository;
import com.glint.chat.repo.OutboxRow;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxRelay {
    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, Clock clock,
                       @Value("${chat.relay.batch-size:100}") int batchSize) {
        this.outbox = outbox; this.publisher = publisher; this.clock = clock; this.batchSize = batchSize;
    }

    /**
     * One polling round. Rows are locked (SKIP LOCKED) for the whole transaction. If a send fails the exception
     * rolls the transaction back, so the rows stay unpublished and the next round retries them (at least once).
     */
    @Transactional
    public int relayOnce() {
        List<OutboxRow> rows = outbox.lockUnpublishedBatch(batchSize);
        for (OutboxRow row : rows) {
            publisher.publish(row);
            outbox.markPublished(row.id(), clock.instant());
        }
        return rows.size();
    }
}
