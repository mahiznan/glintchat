# AMQ Chat Training Program: Design

Date: 2026-10-09
Goal: Become technically strong in messaging (Red Hat AMQ / ActiveMQ Artemis vs Kafka) by building a real-time RM-to-customer bank chat, from broker basics to a multi-pod WebSocket system, with a 50K-user capacity assessment.

## Decisions

| Area | Choice |
|---|---|
| Broker | Apache ActiveMQ Artemis, pinned to `apache/activemq-artemis:2.44.0` (latest on Docker Hub when checked). Red Hat AMQ Broker is built on Artemis but lags upstream, so Module 2 compares features against your production version. |
| Load assumption | 50K registered users, **1K concurrent** online (design target), plus a 3-5x stress case in Module 11 |
| Backend | Java 21, Spring Boot |
| Database | MariaDB (Docker) |
| Presence/cache | Redis (Docker), introduced in Module 8 Phase 2 |
| Frontend | React with Module Federation: shell + RM console + customer widget |
| Environment | Docker Compose on the local machine (Docker 29, Compose v5, Node 26, Java 21 available) |
| Comparison | Kafka (Docker, KRaft mode) for Modules 3-4 only |

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
   - checks uniqueness of `(conversationId, clientMsgId)` (idempotency: a retry returns the original result);
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
Gate: design review; you defend every choice.

### 7. Microservices
`chat-service` (Spring Boot): REST endpoint, MariaDB persistence with an idempotent unique key on `(conversationId, clientMsgId)` and per-conversation `seq`, transactional outbox table, outbox relay publishing to Artemis with publisher confirms (see "Persist-before-publish"), DLQ handling. Tests for each behaviour.
Gate: tests pass; failure drills (DB down, broker down mid-relay, crash between commit and publish, duplicate publish, poison message). Proof that no event is ever published without a committed row.

### 8. WebSocket gateway and multi-pod routing
Single gateway pod first (JWT auth, heartbeat, local socket map).

**Phase 1: broadcast.** Every pod has its own subscription (multicast address, one queue per pod) and receives every message. Each pod checks its local `userId -> sockets` map: deliver if present, ignore otherwise. Lessons: wasted deliveries grow with pod count (measure with 2, 5, 10 pods); per-pod queue lifecycle and orphan queues after scale-down or crash; durable vs non-durable per-pod queues.

**Phase 2: Redis presence.** On connect, write `user:{id} -> podId` with TTL; heartbeat refreshes; remove on disconnect. The sender's pod looks up the recipient's pod and publishes only to that pod's queue. Lessons: stale presence after a crash, race when the user reconnects to another pod mid-flight, offline path (store and notify), multiple devices or tabs per user. Compare Phase 1 vs Phase 2 under load and decide when each is justified.
Gate: cross-pod delivery works with 3 pods; you can explain the cost and failure modes of both phases.

### 9. Delivery guarantees, reconnect, scaling and deploys
Layers of guarantee: producer confirms and persistent messages; broker-to-consumer manual ack, redelivery, DLQ; consumer-to-client application ACK. At-least-once made effectively-once with client message ids, idempotent persistence and client de-duplication. Per-conversation sequence numbers and gap detection. The outbox pattern from "Persist-before-publish" is re-tested here under pod kills and broker restarts.
Reconnect: exponential backoff with jitter (avoid reconnect storms); resume protocol where the client sends `lastSeenSeq` per conversation and the server replays from MariaDB; graceful shutdown on SIGTERM (stop accepting, send "reconnect now", drain in-flight, ack/nack, close cleanly within the grace period); Kubernetes concepts (readiness, preStop, rolling update surge, WebSocket load balancing).
Drills (Compose): `docker kill` a pod mid-conversation; scale 3 -> 1 -> 3; simulated rolling deploy.
Pass criteria: no lost messages, no user-visible duplicates, conversation resumes.

### 10. MFE wiring
React Module Federation: shell hosting RM console and customer widget; shared WebSocket client module with reconnect/resume; auth token flow. End-to-end test with two browsers.
Gate: live chat works across pods through Artemis; survives a pod kill.

### 11. Production readiness and 50K capacity report
Load test (50K registered; **1K concurrent** as the design target, 3-5K as a stress case), connection counts, message rates, broker memory/paging/disk, noisy-neighbor analysis on the shared prod broker, security (TLS, per-address roles, quotas), observability (metrics, tracing, alerts), runbook.
Deliverable: written capacity report answering "is the existing production AMQ OK?" with evidence, plus the platform-team question list from Module 2.

## Preliminary answer on using the existing production AMQ
Likely yes with conditions; to be proven in Module 11, not assumed. Volume for 50K users is small for Artemis. The risks are concurrent connections, noisy neighbors (paging and memory limits shared with other apps), isolation (own address namespace, roles, limits), journal and disk sizing, and the HA topology of the existing setup.

## Out of scope (YAGNI)
Kubernetes cluster setup (concepts only; Compose simulates pods), Kafka beyond the comparison, file attachments, chat bots, end-to-end encryption, multi-region.

## Open items
- Production Artemis version and topology are unknown. Module 2's question list resolves this.
- The 1K concurrent figure is an assumption you gave; validate it against real traffic before sign-off. At that size, Phase 1 broadcast is cheap, so the Redis Phase 2 is a learning step and a scale-headroom decision, not a necessity. Module 8 will measure that.
