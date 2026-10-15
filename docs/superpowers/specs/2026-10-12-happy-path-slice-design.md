# Happy-path slice: design

A thin vertical slice that proves the chat end to end before the learning continues: a customer and an RM, each in a browser tab, send messages to each other live; every message is stored in MariaDB first and travels through Artemis. Built after Module 6's design work and with Module 1 paused (user decision, 2026-10-12: the labs were too dry; resume them once something runs).

Design sources: `docs/chat-design.md` (schema, address, envelope), `docs/design-decisions.md` (D2-D5, D9-D11, D14, D16, D19), `docs/sequence-diagrams.md` (diagram 1, happy path).

## Scope

**In the slice**
- `chat-service`: send a message (idempotent, per-conversation `seq`, transactional outbox), history by `afterSeq`, an outbox relay that publishes to `chat.events`.
- `ws-gateway` (one pod): WebSocket endpoint with a stubbed login, a non-durable named queue per pod on `chat.events`, delivery of events to the local sockets of the participants, ACK to the sender.
- A plain HTML + JavaScript page, opened in two tabs.
- A seed script that creates one conversation with one customer and one RM.

**Out of the slice (deferred on purpose)**: real authentication, adding a second RM, delivered and read states, Redis presence, push notifications, retention, reconnect and gap handling beyond reloading the page, the versioned client contract, `chat.notifications`, address settings (`FAIL`, 60-second TTL, drop on expiry), `max-consumers`/slow-consumer hardening beyond the queue creation, failure drills, React/Module Federation.

## Technology (decided with the user, 2026-10-12)
- Java 21. **Spring Boot 4.1.1** (the user's production uses Spring Boot 4.x; the version lives in one place in each `pom.xml`). Maven. Spring `JdbcTemplate` with plain SQL, Flyway migrations, Spring JMS over the Artemis Core protocol (open question JMS vs AMQP stays for Module 2), JUnit 5 with Testcontainers (MariaDB 11.4, Artemis 2.43.0).
- Starters confirmed against start.spring.io for 4.1.1: `spring-boot-starter-webmvc`, `-jdbc`, `-flyway` (plus `flyway-mysql` for MariaDB), `-artemis`, `-websocket`; test starters `*-test`, `spring-boot-testcontainers`, `testcontainers-mariadb`, `testcontainers-activemq`, `testcontainers-junit-jupiter`. `uuid-creator` 6.1.1 for UUID v7.
- The build must run on Java 21. `mvn` on this machine defaults to Java 27 (Homebrew `openjdk`, `JAVA_HOME` unset), so the build sets `JAVA_HOME` to the 21 JDK and a Maven Enforcer rule fails the build on any other Java.
- Layout: two independent Maven projects, `services/chat-service` and `services/ws-gateway`. Dev ports: chat-service 8080, ws-gateway 8081, MariaDB 3306, Artemis 61616 (the lab stack).

## Data flow
1. The page loads. It fetches history from the gateway (`GET /conversations/{id}/messages?afterSeq=0`, header `X-User-Id`, a development stub; the gateway proxies to chat-service `/internal/...`; **changed 2026-10-15**, the first version called chat-service directly) and opens `ws://localhost:8081/ws?userId=...`. The gateway registers the socket under a new `sessionId` and sends `{type: ready, sessionId}`.
2. The user sends. The page sends `{type: send, conversationId, clientMsgId, body}`. The gateway calls chat-service `POST /internal/conversations/{id}/messages` with `userId` and `originSessionId`. The gateway never publishes (D5).
3. chat-service runs one transaction: lock the conversation row, check membership, check idempotency `(conversationId, senderId, clientMsgId)`, assign `seq = last_seq + 1`, insert the message, insert the outbox row with the envelope (participants snapshot), commit. It returns `{messageId, seq, duplicate}`; the gateway sends `{type: ack, clientMsgId, messageId, seq}` to the origin socket only.
4. The relay (polling, `SELECT ... FOR UPDATE SKIP LOCKED`) publishes each unpublished row to the topic `chat.events` (non-persistent, blocking send so the broker confirms) and marks it published in the same transaction.
5. The gateway pod's own queue `chat.events::<podId>` receives the event. For every participant it finds the local sockets and sends `{type: message, conversationId, message}`; the socket whose `sessionId` equals `originSessionId` is skipped (it already has the ACK); the sender's other tabs and the recipient get the message.
6. The page dedupes by `messageId`, orders by `seq`, and matches an ACK to its pending bubble by `clientMsgId`.

## Interfaces

Internal send (gateway to chat-service; development: no service authentication):
```
POST /internal/conversations/{conversationId}/messages
{ "userId": "customer-1", "originSessionId": "uuid", "clientMsgId": "uuid", "type": "text", "body": "hello" }
-> 200 { "messageId": "uuid", "seq": 1, "clientMsgId": "uuid", "duplicate": false }
-> 403 not an active participant; 404 unknown conversation; 400 invalid body
```
History (page to the gateway, which proxies to chat-service `GET /internal/conversations/{conversationId}/messages`; CORS on the gateway; **superseded 2026-10-15**, see D18):
```
GET /conversations/{conversationId}/messages?afterSeq=0&limit=50     header X-User-Id
-> 200 { "messages": [ { "messageId","seq","senderId","clientMsgId","type","body","createdAt" } ] }
```
Only messages with `seq >= participant.visible_from_seq` are returned (server-enforced).

Event envelope on `chat.events`: the shape in `docs/chat-design.md` section 5 (`schemaVersion` 1, `eventId`, `eventType: message.created`, `occurredAt`, `traceId`, `conversationId`, `participants`, `originSessionId`, `message` with body). Broker properties: `eventType`, `conversationId`, `eventId`.

WebSocket frames (JSON): client to server `send`; server to client `ready`, `ack`, `message`, `error {clientMsgId, code}`.

## Error handling in the slice
- Relay: if the broker send fails, the transaction rolls back, the row stays unpublished and the next poll retries it. Duplicates are possible (D9) and absorbed by the page's `messageId` dedupe.
- Gateway: if chat-service is down or answers an error, the gateway sends an `error` frame with the `clientMsgId`; the page marks the bubble failed.
- A gateway pod whose queue cannot be created does not start (fails fast).

## Verification
- Automated (Testcontainers): idempotent retry returns the same `messageId` and `seq` (S17 style, including concurrent duplicates); concurrent sends give gap-free `seq`; a rolled-back send leaves no outbox row; the relay publishes and marks published; the gateway routes events to the right sockets and skips the origin; the pod queue exists with the intended settings, rejects a second consumer, and every pod queue gets a copy.
- Manual: two tabs chat with each other; the rows are in MariaDB.

## Risks and items to verify while building
1. Spring Boot 4 / Spring Framework 7 / Jackson 3 differ from 3.x (package names such as `tools.jackson`, test annotations). Each step compiles and runs before the next.
2. Creating a named, non-durable, `max-consumers 1` queue with Spring JMS needs the Artemis API; behavior of `autoDelete` for an explicitly created queue is not verified (Task 7 spike).
3. The relay's confirmation relies on `blockOnNonDurableSend=true` in the broker URL, because non-persistent sends are otherwise fire-and-forget. To verify.
4. The relay holds database row locks while it sends to the broker. Acceptable at slice scale; revisit under load (Module 9).
5. `mvn` on Java 27 versus 21 (see above).
