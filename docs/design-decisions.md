# Design Decisions

A running record of decisions made while building the RM-to-customer bank chat, with the problem each one solves, the alternatives rejected, and the gotchas. Use it as a checklist for the real system.

**Status values**: *Decided* (reasoned and agreed), *To verify* (reasoned, but to be proven by an experiment in the named module).

**Load assumptions**: 50K registered users, 1K concurrent. About 1 message per user per 30 s = ~33 msg/s average; peak 3-5x = ~100-170 msg/s. These are assumptions to confirm with real traffic data.

---

## D1. Pin image versions; commit `.env.example`, ignore `.env`
- **Problem**: `latest` tags change under you; a teammate or CI pulling later gets a different version with no code change. Real credentials must never enter git history.
- **Decision**: pin exact versions (Artemis `2.44.0`, MariaDB `11.4`). Commit `infra/.env.example` with placeholders; `infra/.env` is git-ignored. In production consider pinning by digest and using a secret manager instead of `.env`.
- **Gotcha**: `.gitignore` does not untrack files already committed. Ignore from day one.
- **Status**: Decided.

## D2. The broker is a transport; the database is the source of truth
- **Problem**: where does chat history live?
- **Decision**: MariaDB holds all chat state. Artemis only moves events between services.
- **Why**: a broker deletes messages after acknowledgement, cannot be queried ("conversation 42, seq > 17"), and is not for audit/compliance retention. Losing a broker volume must be recoverable from the DB; the reverse is not true.
- **Status**: Decided.

## D3. Persist before publish
- **Problem**: if users see a message live that history does not contain, history is wrong.
- **Decision**: an event may only reach the broker after its message is committed in MariaDB.
- **Status**: Decided.

## D4. Transactional outbox instead of distributed transactions (XA/2PC)
- **Problem (dual write)**: a DB commit and a broker publish are two systems. Any ordering has a crash window leaving them inconsistent: publish first = phantom message not in DB; commit first with the publish held in memory = message nobody publishes.
- **Decision**: in one DB transaction, insert the message row and an `outbox` row. A relay later publishes outbox rows and marks them published.
- **Rejected**: XA/2PC (locks held across systems, slow, in-doubt transactions if the coordinator crashes, ties services together).
- **Outcome by crash point**: before commit = nothing happens, sender gets an error and may retry; after commit before publish = delayed, not lost; after publish before marking = duplicate.
- **Consequence**: at-least-once delivery. See D9.
- **Status**: Decided.

## D5. chat-service owns DB writes and publishing; the gateway never publishes
- **Problem**: why not have the gateway call chat-service, then publish itself on success?
- **Decision**: only chat-service publishes chat events, through the outbox relay. The gateway only holds sockets and calls chat-service.
- **Why the relay lives in chat-service**:
  - The outbox row must be written in the same DB transaction as the message, and the relay reads that table. Only chat-service has the DB connection and transaction.
  - A gateway keeps the "to do" in memory; a crash after the DB commit loses it. The outbox keeps it in the database, so any pod can finish the job.
  - Retry must not depend on the sender's browser staying open or re-sending.
  - Gateway pods stay stateless apart from sockets, scale independently, and need no DB access or broker publish rights.
  - One place controls publish order, tied to the DB `seq`.
- **Alternative (viable but weaker)**: gateway publishes after success and the sender's client re-sends with the same `clientMsgId` until acked. Works only while the sender stays connected.
- **Status**: Decided.

## D6. Outbox relay: polling in Spring Boot
- **Decision**: `@Scheduled(fixedDelay)` (not `fixedRate`, which can stack runs) with `@Transactional`, reading unpublished rows in batches, publishing with `JmsTemplate`, then setting `published_at`.
- **Sizing (estimates)**: poll every 50-100 ms, batch ~100 (about 1,000/s capacity). Adaptive: if a batch comes back full, poll again immediately; sleep only when short or empty.
- **Keep it cheap**: index `(published_at, id)`; delete or archive published rows after a retention window so "find unpublished" stays fast.
- **Bound the lock time**: the transaction holds row locks while publishing. Keep batches small and set a send timeout so a stalled broker cannot hold locks for long.
- **Alternative**: CDC (Debezium on the MariaDB binlog) is faster and more complex; an upgrade path, not a starting point.
- **To verify (Module 7)**: that a persistent JMS send over Artemis CORE blocks until the broker confirms (believed to be the default), and the real latency/throughput at the chosen interval.
- **Status**: Decided for approach; numbers to verify (Modules 7, 11).

## D7. Row locking with `FOR UPDATE SKIP LOCKED`
- **Problem**: several chat-service replicas must not pick the same outbox rows at the same moment.
- **Decision**: select the batch with `FOR UPDATE SKIP LOCKED` so each transaction locks only its own rows and others skip them. This is **row-level** locking, not a table lock, so replicas do not block each other. Requires MariaDB 10.6+ (we run 11.4).
- **Lock lifetime**: the lock follows the transaction (commit, rollback, or connection drop on crash), not a timer.
- **Limit**: it prevents overlap, not duplicates after a crash, and it does not preserve ordering across relays.
- **To verify (Module 7)**: that the relay does not take gap locks that block inserts into `outbox` under the default isolation level; consider `READ COMMITTED` for the relay transaction.
- **Status**: Decided; locking behaviour to verify.

## D8. ShedLock (leader lock): only one relay active at a time
- **Problem**: with several relays each taking different slices, messages from one conversation can be published out of order (pod B finishes row 11 before pod A publishes row 10).
- **Decision**: one active relay, chosen by a lease-based lock (ShedLock or MariaDB `GET_LOCK`). Standby pods take over after the lease expires. One relay is far above our needed throughput (100-170 msg/s).
- **Why layers**: a paused leader (GC pause, network stall) can wake after its lease expired while a standby has taken over, so two relays briefly run. Therefore keep `SKIP LOCKED` (D7) and consumer dedupe (D9) underneath. Never trust one layer.
- **Alternatives**: partition the outbox by `hash(conversationId) % N` with one relay per partition (more parts); or let consumers reorder by `seq` (display is fine, live arrival can be briefly out of order).
- **Status**: Decided.

## D9. At-least-once delivery with idempotency everywhere
- **Problem**: retries and failover cause duplicates; "publish" and "mark published" cannot be atomic across two systems.
- **Decision**:
  - Insert path: unique key on `(conversationId, clientMsgId)`; a retry returns the original result.
  - Publish path: events carry `messageId`; consumers de-duplicate.
  - Client: de-dupes by `messageId` and uses per-conversation `seq` to detect gaps.
- **Principle**: dedupe is part of the design, not a patch.
- **Status**: Decided.

## D10. Three legs of delivery, three retry mechanisms
| Leg | Retried by | From |
|---|---|---|
| chat-service to Artemis | outbox relay | `outbox` table |
| Artemis to consuming gateway pod | broker redelivery of unacknowledged messages | broker journal |
| gateway pod to browser | gateway until the client's application ACK; after a pod death, client resumes with `lastSeenSeq` | memory, then MariaDB |

A missing consumer ACK never goes back to the outbox; the outbox protects only the first leg.
- **Status**: Decided.

## D11. Per-pod queues need a lifecycle policy (Module 8 detail)
- **Problem**: a durable per-pod queue left behind after a permanent scale-down grows unbounded; a non-durable queue vanishes with its consumer and loses the events published meanwhile.
- **Decision direction**: do not rely on the broker for gap recovery. Recovery comes from MariaDB plus the client's `lastSeenSeq` resume. For queue hygiene use message TTL/expiry, a max address size, and auto-delete of queues; avoid `BLOCK` on a shared broker without isolation.
- **To decide (Module 8)**: durable vs non-durable per-pod queues, concrete limits.
- **Status**: To verify (Modules 8, 9).

## D12. Detect unrouted messages
- **Problem**: a message sent to an address with no bound queue is silently dropped and the producer sees success.
- **Decision**: configure `send-to-dla-on-no-route` and alert on dead-letter queue depth. In production, disable queue auto-creation so missing queues are visible.
- **Status**: To verify (Module 2/5: confirm setting and behaviour on our version).

## D13. Least privilege and readiness in infrastructure
- **Decision**: services use the dedicated `glint` DB user, never root. Healthchecks go beyond "port open" (MariaDB `connect` + `innodb_initialized`); dependent services wait on `condition: service_healthy`.
- **Status**: Decided.

## D14. Open decisions
- Client protocol for Spring services: JMS over CORE vs AMQP 1.0 (Module 2). Also compare features against the production Red Hat AMQ version, which lags upstream.
- Concurrency assumption (1K) and message rate need confirmation with real data (Module 11).
- Production topology, address-settings, limits and HA of the existing AMQ (platform-team question list from Module 2).
