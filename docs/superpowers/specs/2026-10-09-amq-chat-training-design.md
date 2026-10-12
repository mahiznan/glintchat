# AMQ Chat Training Program: Design

Date: 2026-10-09
Goal: Become technically strong in messaging (Red Hat AMQ / ActiveMQ Artemis vs Kafka) by building a real-time RM-to-customer bank chat, from broker basics to a multi-pod WebSocket system, with a 50K-user capacity assessment.

## Decisions

| Area            | Choice                                                                                                                                                                                                                            |
| --------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Broker          | Apache ActiveMQ Artemis, pinned to `apache/activemq-artemis:2.43.0`, the same upstream version number as your production broker (2.44.0 was used for the first labs; 2.44.0 is a small release and nothing in our labs depends on it). A matching version number is not identical behaviour: Red Hat builds carry their own patches, so Module 2 and Module 11 confirm against the real production broker. Red Hat AMQ Broker is built on Artemis but lags upstream, so Module 2 compares features against your production version. |
| Load assumption | 50K registered users, **1K concurrent** online (design target), plus a 3-5x stress case in Module 11                                                                                                                              |
| Backend         | Java 21, Spring Boot                                                                                                                                                                                                              |
| Database        | MariaDB (Docker)                                                                                                                                                                                                                  |
| Presence/cache  | Redis (Docker). Running in the environment from Module 0; first used in Module 8 Phase 2 (routing optimisation) and for online/offline presence and push decisions (Module 9b)                                                                                                                                                                                    |
| Frontend        | React with Module Federation: shell + RM console + customer widget                                                                                                                                                                |
| Environment     | Docker Compose on the local machine (Docker 29, Compose v5, Node 26, Java 21 available)                                                                                                                                           |
| Comparison      | Kafka (Docker, KRaft mode) for Modules 3-4 only                                                                                                                                                                                   |

## Architecture (target state)

    Customer MFE --ws--> gateway pod (xN) --publish--> Artemis <--consume-- gateway pods
    RM MFE       --ws-->                                 |
                                                         +--> chat-service --> MariaDB
    gateway pods <--> Redis (presence, Phase 2)

- Gateway pods own the sockets and stay stateless apart from open connections.
- chat-service persists messages (MariaDB is the source of truth). The broker is transport only.
- Browsers never talk to the broker.

Rejected alternatives: Spring STOMP broker relay (hides the messaging concepts), browser-to-Artemis direct (exposes the broker, weak per-user authorization).

## Persist-before-publish (core consistency rule)

Rule: **a message is only delivered to the recipient after it is durably committed in MariaDB.** What a user sees live must never be absent from history.

The two services are separate (ws-gateway holds sockets; chat-service owns the database), and a database commit and a broker publish cannot share one atomic transaction without XA (rejected: slow, fragile, poor fit for MariaDB plus Artemis). The design:

1. Sender's browser sends `{clientMsgId, conversationId, body}` over the WebSocket to its gateway pod.
2. The gateway calls chat-service synchronously (REST/gRPC). **The gateway never publishes chat messages to Artemis itself.**
3. chat-service, in **one MariaDB transaction**:
   - checks uniqueness of `(conversationId, senderId, clientMsgId)` (idempotency: a retry returns the original result);
   - assigns the per-conversation `seq`;
   - inserts the message row;
   - inserts an `outbox` row with the event to publish.
4. chat-service replies success `{messageId, seq}` only after commit. The gateway sends the sender an ACK ("sent"). On failure the sender sees an error and can retry with the same `clientMsgId`.
5. An **outbox relay** (inside chat-service) reads unpublished outbox rows in order and publishes them to Artemis with a persistent message and publisher confirm, then marks them published. A crash between publish and mark causes a republish, so consumers must be idempotent (dedupe on `messageId`).
6. Gateway pods consume the event and push it to the recipient's socket. The recipient's client dedupes by `messageId` and uses `seq` to detect gaps.

Why this satisfies the rule: the event exists in the broker only if the outbox row committed with the message. The failure cases:

- DB commit fails -> nothing published, sender gets an error.
- Crash after commit, before publish -> the relay publishes on restart (delayed, never lost, never phantom).
- Publish succeeds, mark fails -> duplicate publish, absorbed by dedupe.

Alternatives considered:

- **Gateway publishes a command to a queue, chat-service consumes and persists:** async and decoupled, but the sender gets no synchronous "persisted" answer, and the DB write still needs idempotency. Discussed as a variation in Module 6.
- **Publish first, persist in a consumer:** rejected, it breaks the rule above.
- **Debezium CDC instead of a polling relay:** a later upgrade path, discussed in Module 9 and not built.

Relay design questions covered in Module 7: polling interval vs latency, ordering (single relay per conversation partition or `ORDER BY id` with a single active relay), multiple chat-service replicas (use `SELECT ... FOR UPDATE SKIP LOCKED` or leader election), and outbox cleanup.

## Identifiers

Every id has one job. Do not reuse one for another purpose (for example, `messageId` is identity and `seq` is position).

| Identifier | Generated by | Scope / lifetime | Format | Purpose |
|---|---|---|---|---|
| `userId` | Identity provider (taken from the validated token) | Permanent, global | bank's customer / employee id | Who the person is. Never trusted from a client message. |
| `deviceId` | Client app, once per install (browser: local storage) | Stable per install, unique per user | UUID v4 | Origin detection, per-device presence, push-token registry. Bound server-side to the authenticated `userId`. |
| `sessionId` | Gateway, per WebSocket connection | Ephemeral; new on every reconnect | UUID v4 | Key of the pod's local socket map; part of the Phase 2 Redis presence entry. |
| `podId` | Runtime (pod name / hostname) | Per pod instance | string | Names the pod's queue; value in presence entries. |
| `conversationId` | chat-service, at creation | Permanent, global | UUID v7 (proposed, same reasoning as `messageId`) | Unit of ordering, membership and history. `conversation_participant` maps it to users. |
| `clientMsgId` | Sending device, per message | Unique per sender | UUID v4 | **Idempotency key** for retries. The same message re-sent after a crash carries the same id. |
| `messageId` | chat-service, at insert | Permanent, global | **UUID v7 (decided)** | Canonical id of a stored message. Used in events, dedupe, receipts and push dedupe. |
| `seq` | chat-service, inside the DB transaction | Per conversation, monotonic, gap-free | integer | Ordering, gap detection, resume (`lastSeenSeq`). |
| `outboxId` | MariaDB | Global, internal only | auto-increment BIGINT | Relay ordering and cursor. Never leaves chat-service. |
| `eventId` + `eventType` | chat-service, with the outbox row | Per event | UUID v7 (proposed) + `message.created` / `message.delivered` / `message.read` | Delivered and read are separate events, so `messageId` alone does not identify them. |
| `traceId` | First service that sees the request | Per request, carried across all hops including the outbox and broker | UUID | End-to-end debugging. |
| `pushToken` | Provider (FCM/APNs) via the device | Per device; can rotate | provider string | Stored in the device registry; removed on an "invalid token" response. |

Cursors (not identifiers): `lastSeenSeq` per conversation on the client; `lastReadSeq` per `(conversationId, userId)` on the server.

**Which id does which job**
- Sender retry without duplicate: unique key `(conversationId, senderId, clientMsgId)`.
- ACK matched to the optimistic bubble: `clientMsgId` in the ACK.
- Origin socket gets an ACK only; every other socket of the sender (other tabs, other devices) gets the full message: `originSessionId` in the event compared with each local socket's `sessionId`. (`deviceId` is not precise enough: two tabs of one browser share it.)
- A pod decides whether it holds a recipient: participant `userId`s from the event, looked up in the pod's `userId -> sessionId` map.
- Duplicate publish absorbed by the consumer: `eventId`.
- Client shows a message once even when live delivery and resume both send it: `messageId`, plus `clientMsgId` to match a pending bubble.
- Order, gap detection, catch-up: `seq` and `lastSeenSeq`.
- Delivered / read: `(messageId, userId)` for delivered; `lastReadSeq` for read.
- Push only to devices with no live socket: per-device presence; one push per message per device: dedupe key `(messageId, deviceId)`.

**Rules**
- `messageId` is UUID v7 (time-ordered, RFC 9562). Reason: it is a primary key on a large write-heavy table; time-ordered ids insert near the end of the InnoDB index instead of at random positions (no page splits or fragmentation). `clientMsgId` and `deviceId` stay UUID v4: they are generated on devices and never a primary key.
- UUID v7 and ULID order depends on the clocks of the generating machines. Message order is **never** derived from the id; `seq` is the source of truth.
- `seq` must not be the DB auto-increment. Auto-increment is global and can skip numbers on rollback. Increment `conversation.last_seq` in the same transaction as the message insert (this serialises writes within one conversation only).
- A UUID v7 embeds its creation time. That is harmless for a message id, but never use it as a secret token.
- The broker's own message id is not a business id: a republished event gets a new one. Dedupe on `eventId`.
- `senderId` in the idempotency key `(conversationId, senderId, clientMsgId)` is the authenticated **`userId`**, never the `sessionId` or `deviceId`. A new `sessionId` is created on every reconnect, so keying on it would let a re-send after a reconnect create a duplicate. `clientMsgId` must be a UUID v4: with per-device counters, two devices of one user (or a reinstall, or a crash before the counter is saved) would collide on the key, and the server would silently drop the second message while the client shows "sent".
- Never trust `userId` or `deviceId` from the client payload; derive `userId` from the token and bind the `deviceId` to it.
- To verify (Module 7): whether MariaDB's native `UUID` type preserves v7 time ordering in the index; if not, store as `BINARY(16)`, which sorts bytewise. UUID v7 generation: the JDK's `java.util.UUID` has no v7 generator (checked on JDK 21.0.12), so chat-service uses the `uuid-creator` library (decided). When adding the dependency, verify the current version, licence and maintenance status, and wrap it in a small `IdGenerator` class so it can be swapped.
- To verify (Modules 5/7): Artemis duplicate-ID detection (`_AMQ_DUPL_ID` header set to `eventId`) as an optional extra layer; consumer-side dedupe stays regardless.

Decided: a conversation belongs to one customer and one RM. **Reassigning a customer to another RM creates a new conversation** with a new `conversationId` and its own `seq` starting at 1; the old conversation is closed (status, not deleted; retention and audit apply). No extra business ids (tenant, branch, channel) for now; revisit if the real system needs them.
Proposed, to confirm in Module 6: the customer sees both conversations as one thread list, grouped by RM; the previous RM loses live access after the transfer but the record is kept for audit; the new RM does not see the old conversation unless a bank policy says otherwise; if a customer is reassigned back to a former RM, a new conversation is created (no reopening).

## Client sync protocol (MFE and mobile)

Goal: after any connect, reconnect or pod restart, each conversation on screen is complete, in order and free of duplicates, with no gap and no phantom message. The same algorithm is implemented in the web MFE and the mobile app; write it once as this spec and test both against the cases below.

**Principles**
- MariaDB is the source of truth; the live socket is an optimisation. A missed event is always recoverable by REST.
- `seq` is per conversation and gap-free, so "complete" means contiguous `seq`.
- The client advances `lastSeq` (per conversation) only contiguously, never by jumping.
- Overlap between REST and live events is expected and harmless (dedupe by `messageId`). A gap is never harmless.

**State per open conversation**: `lastSeq` (highest contiguous `seq` applied), `pending` (live events held because of a gap, keyed by `seq`), `messages` (displayed, ordered by `seq`).

**A. Connect (new device or reconnect)**
1. Request a socket ticket, open the WebSocket, and **wait for the server `ready` frame**. A TCP connection being open is not enough: the pod must have authenticated the user and registered the socket in its local map. Buffer all live events from now on, per conversation, and do not display them yet.
2. Load the conversation list with last `seq` and unread counts (REST). Do not fetch history for every conversation; fetch it when a conversation is opened. Live events for conversations that are not open only update unread counters.
3. For each open conversation, fetch history:
   - New device, nothing local: `GET .../messages?limit=N` (last N). Older messages load on scroll-back (`beforeSeq`).
   - Returning device with a local `lastSeq`: `GET .../messages?afterSeq=lastSeq`; loop pages until the page is not full.
4. Merge: take the REST result as `R` (highest `seq` = `Rmax`) and the buffer as `B` (lowest `seq` = `Bmin`).
   - If the buffer is empty: display the history and wait for live events.
   - If `Bmin <= Rmax + 1`: no gap. Union both, drop duplicates by `messageId`, order by `seq`, display.
   - If `Bmin > Rmax + 1`: gap. Fetch `afterSeq=Rmax` again, then merge.
5. Set `lastSeq` to the highest contiguous `seq` displayed.

**B. Live event (steady state)** for `seq = s` in an open conversation:
- `s <= lastSeq`: duplicate, ignore.
- `s == lastSeq + 1`: display, `lastSeq = s`, then drain any `pending` events that are now contiguous.
- `s > lastSeq + 1`: gap. Do **not** display `s`. Put it in `pending`, fetch `GET .../messages?afterSeq=lastSeq`, merge and drain. If the gap is not filled after a bounded number of retries, reload the conversation from the server and show a refreshing state; never stay stuck.
Why this is sound: `seq` is assigned under a per-conversation row lock (`conversation.last_seq`), so when message 12 exists in the database, message 11 is committed too.

**C. Sending**
- Create `clientMsgId` (UUID v4), show the message in a "sending" state, send over the socket.
- The ACK carries `clientMsgId`, `messageId` and `seq`, and means "stored in MariaDB" only (not published, not delivered). It turns the bubble to "sent".
- If no ACK arrives (timeout, socket drop), re-send with the **same** `clientMsgId` after reconnecting. The server's unique key `(conversationId, senderId, clientMsgId)` returns the original `messageId` and `seq`; no second row. Stop and show an error after a bounded number of attempts.
- Events and replayed messages carry `clientMsgId` so the client can match one to a pending bubble and not show it twice.
- The origin socket (`originSessionId`) receives a status update, not the full message; the sender's other sockets (other tabs and devices) and the recipient's devices receive the full message. If the origin socket has died and the client reconnected, the new socket has a new `sessionId` and receives the full message, which the client de-duplicates by `clientMsgId` and `messageId`.

**D. Delivered and read**
- On receiving a message the client sends an application-level delivery ACK; when the user views the conversation it reports `lastReadSeq` (monotonic, never backwards). Both are persisted by chat-service and fanned out as `message.delivered` and `message.read` events to the sender's devices. Receiving the echo of one's own message does not mean delivered.

**E. Required checks (acceptance tests, run on web and mobile clients)**
| # | Scenario | Expected result |
|---|---|---|
| S1 | First load on a new device, quiet conversation | Last N messages shown, empty buffer, live events then append |
| S2 | Live event with `seq` in the REST result | Shown once (dedupe), no gap |
| S3 | Buffer `Bmin = Rmax + 1` (touching, no overlap) | Treated as no gap; shown in order |
| S4 | Buffer `Bmin > Rmax + 1` | Gap detected, refetch `afterSeq=Rmax`, then shown contiguously |
| S5 | REST fetch started before the socket `ready` frame (negative test) | Must not happen; a message committed in between could be lost |
| S6 | Live events 10 then 12 | 12 held, `afterSeq=10` fetched, 11 and 12 shown in order, `lastSeq` goes 10, 11, 12 |
| S7 | Event 11 arrives live just after the gap fetch starts | Shown once, in order, no duplicate |
| S8 | Gap never fills after retries | Conversation reloaded from server; no stuck state |
| S9 | Pod killed mid-conversation; client reconnects to another pod | Missed messages recovered by `afterSeq=lastSeq`; nothing lost, nothing doubled |
| S10 | Send, pod dies after DB commit but before the ACK; the other device of the sender is active | The other device shows the message at once; the original device re-sends with the same `clientMsgId`, gets the original `messageId` and `seq`; one message in history |
| S11 | Send, DB commit failed | Error shown; retry with the same `clientMsgId` creates exactly one message |
| S12 | Re-send of an acknowledged message | Returns the original ids; no duplicate row, no duplicate event |
| S13 | Returning device missed more than one page | Pages looped until `lastSeq` is reached |
| S14 | Live event for a conversation that is not open | Unread counter updates; no history fetched |
| S15 | User not a participant tries to register for a conversation | Rejected by the server |
| S16 | Reconnect storm after a pod dies (many clients at once) | Backoff with jitter; no thundering herd on chat-service |
| S17 | Two requests with the same `(conversationId, senderId, clientMsgId)` reach chat-service at nearly the same time (for example the original request still in flight after its pod died, plus the client's re-send via another pod) | Exactly one message row, one `seq`, one outbox row. The loser hits the unique-key violation, rolls back (releasing its `last_seq` increment), and returns the winner's `messageId` and `seq` as a success. It must never surface as an error to the user, and `seq` must stay gap-free. |

Used in: Module 9 (drills and the server-side replay alternative) and Module 10 (client implementation and gate).

## Teaching format (applies to every module)

1. **Concept:** short explanation with a diagram, written to `notes/NN-topic.md`.
2. **Predict:** before each lab, you write down what you expect to happen.
3. **Run and observe:** a Docker lab or small program. Compare the result with the prediction.
4. **Break it:** a failure drill (kill a container, slow a consumer, fill a queue).
5. **Cross-questions:** 5-8 questions (why, what-if, tradeoff). Answer in your own words.
6. **Gate:** do not move on until the cross-questions are answered satisfactorily. Gaps get a short remediation lab.

Rule: one module at a time, no skipping ahead.

## Folder layout

    glint-chat/
      docs/superpowers/specs/      this design
      notes/                       one note per module (your learning log)
      infra/                       docker-compose files, broker.xml, init SQL
      labs/                        throwaway experiments for Modules 1-5
      services/
        chat-service/              Spring Boot: persist + publish + consume
        ws-gateway/                Spring Boot: WebSocket, auth, fan-out
        notification-service/      Spring Boot: offline push (Module 9b)
      tools/
        fake-push/                 mock FCM/APNs provider with failure injection (Module 9b)
      web/
        shell/  rm-console/  customer-widget/    React Module Federation
      tests/                       load and failure-drill scripts

## Modules

### 0. Setup

Compose skeleton with Artemis, MariaDB and Redis, plus the folder layout. Verify the Artemis console and a MariaDB connection.
Gate: all containers healthy; you can explain each port and volume.

### 1. Messaging fundamentals

Point-to-point vs publish/subscribe, queues vs topics, ack modes, delivery guarantees (at-most/at-least/exactly-once), ordering, back-pressure, competing consumers, durable vs non-durable subscribers.
Gate: oral quiz.

### 2. AMQ / Artemis deep dive

Addresses and queues, routing types (anycast, multicast), message journal, paging and memory limits, redelivery and DLQ, expiry, protocols (AMQP 1.0, JMS, STOMP, MQTT, Core), security (roles, address settings), clustering, live/backup HA, producer flow control, connection limits.
Also produces: **list of questions for the production platform team** (topology, limits, address-settings, disk, HA, multi-tenant isolation).
Gate: explain journal vs paging, anycast vs multicast, and what happens when a consumer is slow.

### 3. Kafka deep dive

Log, topics, partitions, offsets, consumer groups, retention, compaction, replay, ordering per partition, KRaft, delivery semantics.
Gate: compare-and-contrast quiz against Artemis.

### 4. AMQ vs Kafka

Use-case matrix and a bank-specific decision exercise (chat, payments events, audit/event sourcing, notifications, analytics feeds). Why chat fits a broker, and when Kafka would be the better choice.
Gate: justify the choice for 5 scenarios.

### 5. Hands-on AMQ lab

Artemis in Docker; console tour; send/receive with JMS and AMQP clients; kill consumers and watch redelivery; poison message to DLQ; durable subscription surviving a restart; paging under a slow consumer; non-durable multicast subscriber.
Gate: predict-then-observe on each experiment.

### 6. Chat design

Addressing scheme (conversation vs per-user vs per-pod), message schema (client message id, conversation id, server sequence), message states (sent, persisted, delivered, read), presence, RM-to-customer assignment, offline handling, bank constraints (audit, retention, PII, compliance recording).
Model **multiple devices per user** from the start (web and mobile for both RM and customer): sessions are per device, a user is the set of their sessions, and read state and delivery state are tracked per user, not per socket.
Gate: design review; you defend every choice.

### 7. Microservices

`chat-service` (Spring Boot): REST endpoint, MariaDB persistence with an idempotent unique key on `(conversationId, senderId, clientMsgId)` and per-conversation `seq`, transactional outbox table, outbox relay publishing to Artemis with publisher confirms (see "Persist-before-publish"), DLQ handling. Tests for each behaviour.
Gate: tests pass; failure drills (DB down, broker down mid-relay, crash between commit and publish, duplicate publish, poison message). Proof that no event is ever published without a committed row.

### 8. WebSocket gateway and multi-pod routing

Single gateway pod first (JWT auth, heartbeat, local socket map).

**Phase 1: broadcast.** Every pod has its own subscription (multicast address, one queue per pod) and receives every message. Each pod checks its local `userId -> sockets` map: deliver if present, ignore otherwise. Lessons: wasted deliveries grow with pod count (measure with 2, 5, 10 pods); per-pod queue lifecycle and orphan queues after scale-down or crash; durable vs non-durable per-pod queues.

**How the pods and the broker connect (applies to both phases).**
- OpenShift (the Route and Service) only decides **which pod each socket lands on** at connect time; after that the socket is one long-lived connection to that pod, and the router passes bytes through. It plays no part in routing messages.
- Each gateway pod is itself an Artemis client. At startup it creates **its own queue** on the shared multicast address `chat.events` and attaches a listener to it. One address, one queue per pod; no per-pod addresses. The broker copies each event into every pod's queue (Phase 1), and the relay in chat-service publishes without knowing where any user is connected.
- In Phase 2 the relay targets specific pods by sending to a **fully qualified queue name** (`chat.events::<podId>`), while a plain send to `chat.events` remains the broadcast fallback. To verify in the Module 5 lab and Module 8 that sending to an FQQN routes only to that queue on our Artemis version.
- `chat.events` is created **multicast-only** and published to through a `topic://` destination, so a mistaken anycast publish fails instead of splitting events between pods. Each per-pod queue allows **one consumer** (`max-consumers 1`, to verify) to stop a zombie consumer from sharing a restarted pod's events; one listener thread per pod queue. Check that every pod queue's `MESSAGES ADDED` is equal after the same number of events.
- Pod startup order: create the queue, attach the listener, and only then report ready and accept sockets. Otherwise events published in between are missed.
- Prefer a **non-durable** per-pod queue in Phase 1: it disappears with the consumer, so a replaced pod (a new pod name) leaves no orphan queue, and the gap is recovered from MariaDB by resume. A durable queue would accumulate events nobody reads after a permanent scale-down.
- OpenShift router idle timeout (believed to default to 30 s, adjustable per route; verify on the cluster) means the WebSocket heartbeat interval must be shorter, or sockets drop silently.
- Dynamic queue creation needs broker permissions; a strict production broker may disable auto-create. See the platform-team question list.

**Phase 2: Redis presence (introduced after Phase 1 works and is measured).** On connect, write presence per device session with TTL; heartbeat refreshes; remove on disconnect. The sender's pod looks up the recipient's pod(s) and publishes only to those pods' queues. **Broadcast stays as the fallback**: if the lookup fails, Redis is down, or the entry is missing, publish to all pods as in Phase 1. Correctness therefore never depends on Redis, only efficiency does. Lessons: stale presence after a crash (the TTL as a safety net), race when the user reconnects to another pod mid-flight, offline path (store and notify), multiple devices or tabs per user, behaviour when Redis is unavailable. Compare Phase 1 vs Phase 2 under load and decide when each is justified.

**Access rule (decided):** presence is read by exact key (`HGETALL presence:{userId}`), never by searching with `KEYS presence:*`, which blocks Redis's single thread; use an explicit index if a list is needed and `SCAN` only for diagnostics. See D14 in the design decisions.

**Role of Redis (decided).** We start with no Redis and Phase 1 broadcast. Redis is introduced later for three jobs: (1) routing efficiency, with broadcast as the backup; (2) online/offline status, for example a presence indicator in the UI; (3) the push-notification decision in Module 9b ("does this device have a live socket?"), which a separate notification-service cannot answer without shared state. At 1K concurrent, routing alone does not justify Redis (broadcast costs about 100 deliveries/s); presence for push and UI does. Module 9b also compares a presence-free alternative: send the push only if no delivery ACK arrives within N seconds.
Gate: cross-pod delivery works with 3 pods; you can explain the cost and failure modes of both phases.

### 9. Delivery guarantees, reconnect, scaling and deploys

Layers of guarantee: producer confirms and persistent messages; broker-to-consumer manual ack, redelivery, DLQ; consumer-to-client application ACK. At-least-once made effectively-once with client message ids, idempotent persistence and client de-duplication. Per-conversation sequence numbers and gap detection. The outbox pattern from "Persist-before-publish" is re-tested here under pod kills and broker restarts.
Reconnect: exponential backoff with jitter (avoid reconnect storms); resume protocol where the client sends `lastSeenSeq` per conversation and the server replays from MariaDB; graceful shutdown on SIGTERM (stop accepting, send "reconnect now", drain in-flight, ack/nack, close cleanly within the grace period); Kubernetes concepts (readiness, preStop, rolling update surge, WebSocket load balancing).
Drills (Compose): `docker kill` a pod mid-conversation; scale 3 -> 1 -> 3; simulated rolling deploy.
Pass criteria: no lost messages, no user-visible duplicates, conversation resumes.

### 9b. Offline push notifications (mocked FCM/APNs)
Both RM and customer use **web and mobile**. A user can have several devices at once (for example web plus phone), each with its own socket and its own push token. The real FCM/APNs integration is replaced by a small `fake-push` container that mimics both providers' HTTP APIs, records every "would send" call, and can inject failures. The architecture is the same one a real system would use; only the provider endpoint changes.

Build `notification-service` as a **third, independent consumer** of the `chat.events` multicast address, with its own queue, so it cannot slow or break live delivery. Its replicas **share one fixed-name durable queue** (competing consumers: each event processed by one replica; manual acknowledgement after the push; dedupe by `(messageId, deviceId)`; optional message group by `conversationId` for push order). This is the opposite of the gateway pods, which each have their own queue.
- **Device registry** in MariaDB: `device(userId, deviceId, platform [web|android|ios], pushToken, lastSeenAt)`; register and unregister endpoints; token refresh.
- **Decision rule**: send a push to a recipient's devices that have **no live socket**. Presence is per device, not per user (extends the Phase 2 Redis registry from a single `podId` to a set of device sessions). A backgrounded mobile app has dropped its socket, so it gets a push even while the same user's web tab is live.
- **Content-free pushes**: the notification says "You have a new message" and carries only an id. No message text (bank PII and compliance). The app then fetches the message through the normal resume path.
- **Idempotency of an external side effect**: a redelivered event must not send three pushes. Dedupe key per `(messageId, deviceId)`, stored before or atomically with the send; analyse the crash window (send succeeded, record failed).
- **Failure handling against the fake provider**: 5xx or timeout leads to bounded retry with backoff, then dead-letter; "invalid or unregistered token" leads to removing the device token; throttling (429) leads to backoff. Notification rate limiting and collapsing (many messages in one conversation become one push).
- **Race**: a push is sent just as the user reconnects. The client must tolerate a push for a message it already has.
- **Mobile client simulation**: no native apps. A scripted WebSocket client plays the phone (connects, "backgrounds" by disconnecting, receives a push, reconnects and resumes).
Gate: kill and restart the notification-service and the fake provider mid-conversation; no missing pushes for offline devices, no duplicate pushes, no push for devices with a live socket, and the DLQ holds only genuinely failed ones.

### 10. MFE wiring

React Module Federation: shell hosting RM console and customer widget; shared WebSocket client module with reconnect/resume; auth token flow. End-to-end test with two browsers.
Gate: live chat works across pods through Artemis; survives a pod kill.

### 11. Production readiness and 50K capacity report

Load test (50K registered; **1K concurrent** as the design target, 3-5K as a stress case), connection counts, message rates, broker memory/paging/disk, noisy-neighbor analysis on the shared prod broker, security (TLS, per-address roles, quotas), observability (metrics, tracing, alerts), runbook.
Deliverable: written capacity report answering "is the existing production AMQ OK?" with evidence, plus the platform-team question list from Module 2.

## Preliminary answer on using the existing production AMQ

Likely yes with conditions; to be proven in Module 11, not assumed. Volume for 50K users is small for Artemis. The risks are concurrent connections, noisy neighbors (paging and memory limits shared with other apps), isolation (own address namespace, roles, limits), journal and disk sizing, and the HA topology of the existing setup.

## Out of scope (YAGNI)

Kubernetes cluster setup (concepts only; Compose simulates pods), Kafka beyond the comparison, file attachments, chat bots, end-to-end encryption, multi-region, native mobile apps, real FCM/APNs integration (mocked in Module 9b; the real providers' credentials, certificates and quotas are covered as notes only).

## Open items

- Production Artemis version and topology are unknown. Module 2's question list resolves this.
- The 1K concurrent figure is an assumption you gave; validate it against real traffic before sign-off. At that size, Phase 1 broadcast is cheap, so the Redis Phase 2 is a learning step and a scale-headroom decision, not a necessity. Module 8 will measure that.
- Module 8 also tests the **dynamic address/queue per conversation (or per user)** alternative to Redis discovery with real numbers (creation time, broker memory/CPU, journal growth, reconnect storm), against Redis presence at the same load. Reasons it is not the default are in D14.
