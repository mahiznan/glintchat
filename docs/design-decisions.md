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
  - Insert path: unique key on `(conversationId, senderId, clientMsgId)`; a retry returns the original result. Scoping by sender means one user's id can never collide with, or probe, another's.
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
- **Leaning (Phase 1)**: non-durable per-pod queue, because a replaced pod gets a new name and the gap is recovered from MariaDB. The queue is created and the listener attached **before** the pod reports ready.
- **To decide (Module 8)**: durable vs non-durable per-pod queues, concrete limits.
- **Status**: To verify (Modules 8, 9).

## D12. Detect unrouted messages
- **Problem**: a message sent to an address with no bound queue is silently dropped and the producer sees success.
- **Decision**: configure `send-to-dla-on-no-route` and alert on dead-letter queue depth. In production, disable queue auto-creation so missing queues are visible.
- **Status**: To verify (Module 2/5: confirm setting and behaviour on our version).

## D13. Least privilege and readiness in infrastructure
- **Decision**: services use the dedicated `glint` DB user, never root. Healthchecks go beyond "port open" (MariaDB `connect` + `innodb_initialized`); dependent services wait on `condition: service_healthy`.
- **Status**: Decided.

## D14. Live delivery is consumed by the gateway pods; side concerns are separate consumers
- **Problem**: which service listens to the broker for live delivery?
- **Decision**: the gateway pods, because only the pod holding a user's socket can write to it. Chat-service has no sockets and would need to forward to the right gateway anyway (same routing problem plus a hop, and it would become stateful about connections).
- **Phase 1 shape**: one multicast address, one queue **per pod**. The number of queues grows with pods, never with users (not 1K, not 50K). Offline users have no socket, no queue and no broker footprint; their messages wait in MariaDB and are fetched by resume.
- **Side concerns** (offline push, audit, unread counters) are independent consumers with their own queues on the same address, so they cannot slow or break live delivery.
- **Who connects what**: OpenShift only chooses the pod for each socket at connect time. Each pod is an Artemis client with its **own queue** on one shared multicast address (`chat.events`), so there is one address and N queues, never per-pod addresses. Phase 2 targets a pod by sending to the **fully qualified queue name** (`chat.events::<podId>`); a plain send to `chat.events` stays as the broadcast fallback. To verify (Module 5/8).
- **Presence shape for Phase 2**: Redis hash `presence:{userId}`, field `{sessionId}`, value `{podId}|{deviceId}|{platform}`, per-field TTL (~30 s, heartbeat ~10 s; Redis 7.4 hash-field expiry, verified on our 7.4.11). The relay unions the pods of all participants (including the sender's own devices, for multi-device sync) and publishes once per target pod; each pod then decides per socket (origin socket gets a status update only). Redis is a hint; the pod's in-memory socket map stays authoritative.
- **Unknown is not offline**: when Redis is down or has just restarted, presence is empty, so every device would look offline and the notification-service could push to live devices. Treat presence as unknown in that case (delay the push, or use the delivery-ACK rule).
- **Redis, later and as an optimisation**: start with Phase 1 broadcast and no Redis. Add Redis afterwards for (1) targeted routing, always with broadcast as the fallback so correctness never depends on Redis; (2) online/offline status; (3) the push decision, because the separate notification-service cannot see which sockets are live. Compare a presence-free push rule (push only if no delivery ACK within N seconds) in Module 9b. Redis holds only rebuildable data (TTL + heartbeat), so persistence is off (D2).
- **Cost at 1K concurrent, ~25 msg/s, 4 pods**: about 100 deliveries/s from the broker, 75% discarded. Trivial; broadcast is fine here. Measure in Module 8.
- **Status**: Decided; numbers to verify (Module 8).

## D15. Offline push notifications (Module 9b)
- **Problem**: RM and customer use web and mobile. A backgrounded mobile app has no socket, so live delivery cannot reach it.
- **Decision**: a separate `notification-service` consumes `chat.events` on its own queue and sends a **content-free** push to each recipient device **without a live socket**. Presence and device registry are per device. Idempotent on `(messageId, deviceId)`.
- **Why content-free**: push payloads transit third-party providers; for a bank, keep message text out of them.
- **Why a separate service**: independent failure and scaling; provider outages, retries and rate limits must not affect live chat.
- **Learning scope**: FCM/APNs are mocked by a `fake-push` container; only the endpoint differs in production.
- **Status**: Decided for direction; details in Module 9b.

## D16. Identifiers: one id, one job; `messageId` is UUID v7
- **Problem**: retries, multi-device sync, resume, dedupe, receipts and push all need to refer to "the same message" reliably, and mixing roles (identity vs order vs idempotency) causes subtle bugs.
- **Decision**: separate ids for separate jobs. Full table with generator, scope and purpose is in the spec's "Identifiers" section. Key points:
  - `clientMsgId` (device-generated UUID v4) is the idempotency key, scoped to the sender.
  - `messageId` is **UUID v7** (time-ordered), generated by chat-service. `seq` is the per-conversation position and the only source of message order.
  - `eventId` + `eventType` identify events, because delivered and read are separate events.
  - `deviceId` (per install) vs `sessionId` (per connection): origin detection uses the device, the local socket map uses the session.
- **Why UUID v7 over v4**: the primary key of a large write-heavy table. Time-ordered ids append near the end of the InnoDB index instead of splitting pages at random. It is also a standard (RFC 9562) with tooling support, unlike ULID.
- **Gotchas**:
  - Order across machines is only as good as their clocks; never derive message order from the id.
  - `seq` must be incremented in the same transaction as the insert (`conversation.last_seq`), not taken from auto-increment, which is global and can skip numbers.
  - Never trust `userId` or `deviceId` from the client payload.
  - The broker's own message id is not a business id.
- **Rules per identifier** (who generates it, the conditions, and the use cases it serves):

  **`clientMsgId`** (idempotency key for one message)
  - *Generated by*: the sending client (web or mobile), as a UUID v4.
  - *Conditions*: created once, when the message object is created on send, and **tied to that message object, not to its text**. Every retry of that same message reuses the same id (socket resend after reconnect, REST fallback, double-click on Send, app restart). Persist the pending message together with its id (IndexedDB / local DB) **before the first send**, so a crash or reload keeps the id. A deliberately new message, even with identical text, gets a new id. Never regenerate on retry; never reuse for a different message. Disable or ignore a second click on the same pending message.
  - *Use cases*: server-side dedupe via the unique key `(conversationId, senderId, clientMsgId)`; matching the ACK or a replayed message to the optimistic bubble; safe re-send after a pod crash (spec cases S10-S12).
  - *If violated*: regenerated on retry gives duplicate messages; reused across messages makes a real message vanish as a "duplicate".

  **`messageId`** (identity of a stored message)
  - *Generated by*: chat-service only, as UUID v7 (`uuid-creator` behind an `IdGenerator` class), in application code.
  - *Conditions*: generated once, at persist time, inside the transaction, immediately before the insert, and **only on the first successful insert**. A duplicate request (same `(conversationId, senderId, clientMsgId)`) returns the existing `messageId` and does not generate a new one. The same value is written to the message row, the outbox row and the event. Not generated by the DB, a gateway pod, or a client.
  - *Use cases*: primary key; client and consumer dedupe; delivery receipts; push dedupe `(messageId, deviceId)`; event correlation.
  - *If violated*: generated by more than one party or on every retry, the same message gets several identities and dedupe fails.

  **`seq`** (position in a conversation)
  - *Generated by*: chat-service, in the same transaction as the insert, by incrementing `conversation.last_seq` under the row lock. Per conversation, gap-free for committed messages. Never from `AUTO_INCREMENT`, never `MAX(seq)+1`.
  - *Conditions*: assigned only on the first successful insert; a rollback releases the number; a retried duplicate keeps its original `seq`.
  - *Use cases*: ordering, gap detection (`seq == lastSeq + 1`), resume (`afterSeq`), `lastReadSeq`.
  - *Never*: used as identity, and never derived from `messageId` or timestamps.

  **`conversationId`** (conversation identity)
  - *Generated by*: chat-service, at creation, UUID v7 (proposed). Creation is idempotent: the client sends a request id so a retried "start chat" does not create two conversations.
  - *Use cases*: grouping, ordering scope, participant and authorisation checks, address/routing key in events.
  - *Reassignment*: decided. A customer moving to another RM gets a **new** `conversationId` (new `seq` from 1); the old conversation is closed, never deleted. The per-conversation `seq`, `lastSeq` and `lastReadSeq` therefore never need to survive a participant change.

  **`eventId` and `eventType`** (identity of one outbox event)
  - *Generated by*: chat-service, in the same transaction as the outbox row. `eventType` is one of `message.created`, `message.delivered`, `message.read`.
  - *Conditions*: one id per logical event; a **republish by the relay reuses the same `eventId`**, which is what lets consumers dedupe.
  - *Use cases*: consumer-side dedupe; optionally the Artemis duplicate-detection header (to verify). The broker's own message id is not used for this.

  **`outboxId`** (internal ordering)
  - *Generated by*: MariaDB `AUTO_INCREMENT`. Internal to chat-service; never in an API or event. Used for relay order and cursor.

  **`senderId`** (in the idempotency key)
  - *Is*: the authenticated `userId` of the sender, taken from the token. Never `sessionId` (new on every reconnect, would defeat idempotency) and never `deviceId` (would let two devices of one user bypass each other's dedupe unpredictably).
  - *Why `clientMsgId` is a UUID and not a counter*: counters collide across devices, after a reinstall, and after a crash before the counter is saved. The collision is silent: the server treats the new message as a duplicate and the client shows "sent" for a message that was never stored.

  **`userId`**
  - *Generated by*: the identity provider. Read by the gateway from the **validated token**; never from a request body, URL or socket frame the client controls.
  - *Use cases*: authorisation, participant checks, the `userId -> sessions` map, `senderId`.

  **`deviceId`** (one app install)
  - *Generated by*: the client, once, as a UUID v4, then persisted (browser: local storage; mobile: secure storage). It survives reconnects. A cleared browser or a reinstall creates a new device, which is expected.
  - *Conditions*: registered with the server and **bound to the authenticated `userId`**; the server never trusts a device claim by itself. Removed (`DELETE /devices/{deviceId}`) on logout.
  - *Use cases*: push-token registry, per-device presence ("push only to devices without a live socket"), device management. **Not** used for origin detection: two tabs of one browser share it.

  **`sessionId`** (one WebSocket connection)
  - *Generated by*: the gateway pod, UUID v4, **after** authentication and when the socket is registered; sent to the client in the `ready` frame.
  - *Conditions*: new on every connect; never reused after a reconnect.
  - *Use cases*: key of the pod's local socket map; part of the Redis presence entry; **origin detection**: the event carries `originSessionId`, and a pod sends only a status update to the socket whose `sessionId` matches and the full message to all other sockets of the sender.
  - *Why not `deviceId` for origin*: two tabs share a `deviceId`, so the second tab would only get a status update and never show the message.

  **`podId`**
  - *Generated by*: the runtime (pod name or hostname). Names the pod's queue and is the value in presence entries. Not stable across restarts.

  **`traceId`**
  - *Generated by*: the first service that sees the request (the gateway or the client). Copied into the outbox row and the event, so one trace spans the HTTP/socket call, the DB, the broker and delivery.

  **`pushToken`**
  - *Generated by*: the push provider (FCM/APNs) via the device. Stored in the device registry; updated on rotation; deleted when the provider reports it invalid.

- **To verify (Module 7)**: MariaDB native `UUID` type vs `BINARY(16)` for v7 ordering; the exact `uuid-creator` version, licence and maintenance status (library chosen; the JDK 21 has no v7 generator). Wrap it in an `IdGenerator` class inside chat-service so it can be swapped.
- **Status**: Decided (v7 for `messageId`; v7 for `conversationId` and `eventId` is proposed on the same reasoning).

## D17. Client sync protocol: socket first, wait for `ready`, then REST, with contiguous `seq`
- **Problem**: after a connect, reconnect or pod restart the client has two data sources (REST history and the live socket) and a race between them. Wrong ordering loses a message or shows one twice.
- **Decision**: the full algorithm and 16 acceptance checks are in the spec's "Client sync protocol". In short:
  1. Open the socket, wait for the server `ready` frame, buffer live events.
  2. Fetch history over REST (last N for a new device, `afterSeq=lastSeq` for a returning one).
  3. Merge by `seq`: gap-free when `Bmin <= Rmax + 1`, otherwise refetch. Dedupe by `messageId`.
  4. In steady state, accept only `seq == lastSeq + 1`; hold anything ahead of it and fetch the gap.
  5. `lastSeq` advances only contiguously.
- **Why**: the database is complete by construction (D3), so a gap is always recoverable; the overlap is made harmless by dedupe, and the gap check makes holes impossible to miss. The `ready` frame closes the window where the REST query could run before the socket is registered.
- **Cost**: the algorithm lives in two clients (web and mobile). Write it once as a spec and test both against the same cases. Alternative: server-side replay in the WebSocket handshake, which centralises the logic but complicates the gateway (compare in Module 9).
- **Depends on**: per-conversation gap-free `seq` assigned under a row lock (D16).
- **Status**: Decided.

## D18. Open decisions
- Client protocol for Spring services: JMS over CORE vs AMQP 1.0 (Module 2). Also compare features against the production Red Hat AMQ version, which lags upstream.
- Reassignment policy details to confirm in Module 6: customer sees both conversations grouped by RM; previous RM's access after transfer; whether the new RM can see the old conversation; reassignment back creates a new conversation.
- Concurrency assumption (1K) and message rate need confirmation with real data (Module 11).
- Production topology, address-settings, limits and HA of the existing AMQ (platform-team question list from Module 2).
- Platform-team question: can our application role create non-durable queues under `chat.*` (queue-creation permission and auto-create allowed for that pattern), and can per-address limits (max size, TTL, address-full policy) be set so our queues cannot affect other applications?
- Platform-team question: what is the OpenShift router idle timeout for WebSocket routes, and can it be raised per route?
- Redis in production: version (hash-field expiry needs 7.4+; otherwise use sorted sets), HA, and whether a Redis is available to us at all.
