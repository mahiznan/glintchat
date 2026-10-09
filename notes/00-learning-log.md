# Module 0 Learning Log (Artemis + MariaDB layers)

Status: Artemis layer committed. MariaDB layer built and running, not yet committed. Redis layer not started.

## 1. Docker Compose basics
- `up -d --wait`: create and start detached, return when healthy.
- `stop`/`start`: container kept. `down`: container removed, **volume kept**. `down -v`: volume deleted, **data lost**.
- Full command table: `notes/00-docker-commands.md`.
- Pin image versions (`2.44.0`, `11.4`), never `latest`: a moving tag can change behaviour with no code change.
- `.env.example` (committed, placeholders) + `.env` (git-ignored, real values). Committing `.env` and ignoring it later does not work: `.gitignore` does not affect already-tracked files.

## 2. Artemis concepts
**Ports (acceptors)**: 61616 (all protocols), 5672 (AMQP), 61613 (STOMP), 1883 (MQTT), 5445 (HornetQ/STOMP), 8161 (HTTP console, not messaging). Only 61616 and 8161 are published to the host.

**Protocols**: CORE (Artemis native, fastest), AMQP 1.0 (open standard, not the same as RabbitMQ's 0-9-1), OPENWIRE (legacy ActiveMQ Classic), STOMP (simple text, web/WebSocket friendly), MQTT (IoT), HORNETQ (legacy). JMS is a Java API, not a wire protocol. One port serves many protocols because the broker detects the protocol from the first bytes of the connection.

**Addresses, queues, routing**
- Producers send to an **address**; consumers read from **queues** bound to it.
- **Anycast**: message goes to one queue (work sharing). **Multicast**: copied to every queue (pub/sub).
- No queue bound to the address: the message is **silently dropped**, for both routing types. A queue only receives messages that arrive after it exists. A queue is what makes a message wait.
- Clients often auto-create address+queue, so this case can be hidden in dev. Strict production setups disable auto-create.

**Experiments done in the console**: anycast with 1 and 2 queues (split), multicast (copy), no-queue drop, persistence across restart, move/remove messages. All matched predictions.

**Volume loss** (`down -v`): credentials come back (re-created from `.env`), but runtime state is lost: queues, messages, subscriptions, delivery counts, DLQ contents. Backups are point-in-time; HA (live/backup) protects against node failure; a copy of a live journal is not a reliable backup.

**Orphan durable queue** (e.g. after permanent scale-down): grows unbounded unless a TTL/expiry, max address size, or auto-delete is configured. Then paging, then `address-full-policy` (BLOCK/PAUSE/DROP/FAIL). BLOCK can stall other apps on a shared broker.

**Unrouted messages**: `send-to-dla-on-no-route` sends them to the dead-letter address (off by default). Monitor DLQ depth.

**Delivered vs acknowledged**: in a queue is not delivered; handed to a consumer is not acknowledged; only acknowledged messages are removed. Revisit in Module 9.

## 3. MariaDB concepts
- Dedicated app user `glint` limited to database `glint`; root only for admin (least privilege).
- Healthcheck `healthcheck.sh --connect --innodb_initialized` (script lives in the image at `/usr/local/bin/healthcheck.sh`): the port can be open before the DB is ready.
- **Transaction / atomicity**: both inserts commit or neither. Demonstrated with `ROLLBACK` vs `COMMIT`.

## 4. The consistency problem (most important lesson so far)
- Rule: a message is delivered only after it is committed in MariaDB. History must match what users saw.
- Artemis and MariaDB cannot share a transaction without XA/2PC (distributed transaction manager). Rejected: slow, locks held across systems, in-doubt transactions if the coordinator crashes.
- **Dual-write problem**: any order of "write DB" and "publish" has a crash window that leaves the two inconsistent.
- **Transactional outbox**: write the message and an outbox row in one DB transaction; a relay publishes outbox rows to the broker afterwards, then marks them published.
- Crash points: before DB commit = nothing happens, sender sees an error; after commit before publish = delayed, not lost (relay publishes later); after publish before marking = **duplicate** publish.
- Therefore the system is **at-least-once**, and consumers must de-duplicate on `messageId`. A lock reduces overlap, but cannot make "publish" and "mark published" atomic.

## 5. Three legs, three retry mechanisms
| Leg | Retried by | From |
|---|---|---|
| chat-service to Artemis | outbox relay | `outbox` table |
| Artemis to consuming gateway pod | broker redelivery of unacked messages | broker journal |
| gateway pod to browser | gateway until client app ACK; after pod death, client resumes via `lastSeenSeq` | memory, then MariaDB |

## 6. Outbox relay in Spring Boot (concepts; code comes in Module 7)
- `@Scheduled(fixedDelay=...)` (not `fixedRate`), `@Transactional`, `SELECT ... FOR UPDATE SKIP LOCKED`, publish with `JmsTemplate`, set `published_at`.
- Polling, not listening to writes. Listening to writes = CDC (e.g. Debezium on the binlog), a later upgrade.
- Estimated load: 1K concurrent users, ~1 msg per 30 s each = ~33 msg/s; peak 3-5x = 100-170 msg/s. Poll 50-100 ms, batch ~100, adaptive loop (poll again immediately if the batch was full). These are estimates to validate in Module 11.
- Ordering with several relays can break per-conversation order. Chosen: one active relay via a leader lock (ShedLock), with SKIP LOCKED and consumer dedupe as safety nets.
- Failed relay run: exception, rollback, locks released, `published_at` still NULL, retried next poll. A crash drops the connection and MariaDB rolls back. No timeout is involved. A timeout (lease) exists only in the leader lock.

## 7. Corrections I received and want to remember
- Gateway pods do not publish chat messages; the relay in chat-service does.
- Row locks follow the transaction, not a clock.
- Dedupe is part of the design, not a patch.

## 8. Open items
- [ ] Persistence experiment on MariaDB (`down`/`up`, then `down -v`/`up`): record observations.
- [ ] Answer Module 0 cross-questions in `notes/00-setup.md` (own words).
- [ ] Redis layer, README, `tests/smoke.sh`.
