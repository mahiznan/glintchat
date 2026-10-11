# Sequence Diagrams

One diagram per use case, added as we discuss each one. Diagrams use Mermaid (renders on GitHub and in most Markdown viewers). Rules and reasoning live in `docs/design-decisions.md` (D#) and the spec's "Client sync protocol" checks (S#); this file shows the order of events.

**Cast**
- *Customer P*: the customer's phone (device P), connected to gateway pod G1.
- *Customer L*: the same customer's laptop, connected to gateway pod G3.
- *RM*: the relationship manager's browser, connected to gateway pod G2.
- *Gateway Gn*: ws-gateway pod n. Holds sockets, never publishes chat events (D5).
- *CS*: chat-service. *Relay*: the outbox relay inside chat-service (D6, D8).
- *DB*: MariaDB. *MQ*: Artemis, address `chat.events`, multicast, one queue per gateway pod (D14).

Contents
1. Send a message, both users online (happy path)
2. Delivered receipt back to the sender
3. Live gap detected by the client (seq 10 then 12)
4. Reconnect after a pod restart (sync protocol)
5. Origin pod dies after the commit, before the ACK
6. Client re-sends an already stored message
7. Concurrent duplicate sends (S17)
8. DB commit fails
9. Relay publishes, but fails to mark the row (duplicate publish)

---

## 1. Send a message, both users online (happy path)

Checks: spec S2. Decisions: D3, D4, D5, D14, D16.

```mermaid
sequenceDiagram
    autonumber
    participant P as Customer P (phone)
    participant G1 as Gateway pod 1
    participant CS as chat-service
    participant DB as MariaDB
    participant R as Outbox relay
    participant MQ as Artemis chat.events
    participant G2 as Gateway pod 2
    participant G3 as Gateway pod 3
    participant RM as RM browser

    P->>P: Create clientMsgId c-1, show bubble as sending
    P->>G1: WS send (conversationId, clientMsgId c-1, body)
    G1->>CS: POST /internal/messages (conversationId, senderId from token, originSessionId, clientMsgId, traceId)
    CS->>DB: BEGIN
    CS->>DB: Look up existing (conversationId, senderId, clientMsgId) - none
    CS->>DB: UPDATE conversation SET last_seq = last_seq + 1 (row lock), read seq = 18
    CS->>DB: INSERT message (messageId v7, seq 18, ...)
    CS->>DB: INSERT outbox (eventId, message.created)
    CS->>DB: COMMIT
    CS-->>G1: 201 messageId, seq 18
    G1-->>P: ACK (clientMsgId c-1, messageId, seq 18)
    P->>P: Bubble becomes sent (stored in MariaDB, not yet delivered)

    Note over R,MQ: Asynchronous, within about one poll interval
    R->>DB: Select unpublished rows FOR UPDATE SKIP LOCKED
    R->>MQ: Publish event (persistent, wait for broker confirm)
    R->>DB: Mark row published, COMMIT
    MQ-->>G1: Copy into pod 1 queue
    MQ-->>G2: Copy into pod 2 queue
    MQ-->>G3: Copy into pod 3 queue
    G1->>G1: Socket with sessionId = originSessionId gets status only, nothing more to send
    G2->>RM: Push full message over WS (seq 18)
    G3->>G3: No local socket for participants, ignore
```

What to notice
- The ACK in step 11 depends only on the DB commit. The relay and the broker are not in the sender's wait.
- Every pod gets every event (Phase 1 broadcast). Only the pod holding a matching socket delivers.
- Nothing is published unless the outbox row was committed with the message.

---

## 2. Delivered receipt back to the sender

"Delivered" means the recipient's device received the message and its app acknowledged it. It is not the echo of the original event. Decisions: D14, spec section C and D.

```mermaid
sequenceDiagram
    autonumber
    participant RM as RM browser
    participant G2 as Gateway pod 2
    participant CS as chat-service
    participant DB as MariaDB
    participant R as Outbox relay
    participant MQ as Artemis chat.events
    participant G1 as Gateway pod 1
    participant P as Customer P (phone)

    RM->>G2: WS delivery ACK (messageId, seq 18)
    G2->>CS: POST /internal/receipts (delivered, messageId, userId from token)
    CS->>DB: BEGIN
    CS->>DB: Record delivered for (messageId, RM) - idempotent
    CS->>DB: INSERT outbox (eventId, message.delivered)
    CS->>DB: COMMIT
    R->>MQ: Publish message.delivered
    MQ-->>G1: Copy into pod 1 queue
    G1->>P: Push status update (messageId, delivered)
    P->>P: Bubble becomes delivered
```

What to notice
- Delivered and read travel the same route as messages: database first, then outbox, then broker.
- "Read" works the same way, using `lastReadSeq` (monotonic, never moves backwards).

---

## 3. Live gap detected by the client (seq 10 then 12)

Checks: S6, S7, S8. Decision: D17. The client has shown messages up to seq 10 in an open conversation.

```mermaid
sequenceDiagram
    autonumber
    participant G as Gateway pod
    participant C as Client (MFE or mobile)
    participant CS as chat-service
    participant DB as MariaDB

    G->>C: Live event seq 12
    C->>C: lastSeq is 10, 12 is not 11, hold 12 in pending, do not display
    C->>CS: GET /conversations/id/messages?afterSeq=10
    CS->>DB: Select messages with seq greater than 10, ascending
    DB-->>CS: seq 11 and seq 12
    CS-->>C: seq 11, seq 12
    opt Live event seq 11 arrives while the fetch is running
        G->>C: Live event seq 11
        C->>C: Put in the same buffer, dedupe by messageId
    end
    C->>C: Merge by seq, display 11 then 12
    C->>C: lastSeq advances 10, 11, 12 (contiguous only)
    alt Gap not filled after bounded retries (S8)
        C->>CS: Reload the conversation from the server
        C->>C: Show a refreshing state, never stay stuck
    end
```

What to notice
- 12 is never shown before 11.
- If 12 exists in the database then 11 does too, because `seq` is assigned under the conversation row lock (D16).

---

## 4. Reconnect after a pod restart (sync protocol)

Checks: S1 to S5, S9, S13, S16. Decision: D17. The client's pod restarted. It reconnects, with backoff and jitter, to a different pod and holds `lastSeq = 17` for the open conversation.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant G4 as Gateway pod 4
    participant CS as chat-service
    participant DB as MariaDB

    C->>C: Connection lost, wait with backoff and jitter
    C->>G4: POST /ws/ticket (deviceId)
    G4-->>C: Short-lived single-use ticket
    C->>G4: Open WebSocket with ticket
    G4->>G4: Authenticate, register socket (userId to sessionId)
    G4-->>C: ready frame (sessionId)
    Note over C: Fetch history only after ready, otherwise a message could be missed (S5)
    C->>C: Start buffering live events per conversation, do not display
    C->>CS: GET /conversations/id/messages?afterSeq=17
    CS->>DB: Select messages with seq greater than 17
    DB-->>CS: seq 18, 19, 20
    CS-->>C: seq 18, 19, 20 (Rmax = 20)
    alt Buffer is empty
        C->>C: Display history, wait for live events
    else Buffer lowest seq (Bmin) is at most Rmax + 1
        C->>C: No gap, union, dedupe by messageId, order by seq
    else Bmin greater than Rmax + 1
        C->>CS: GET .../messages?afterSeq=20 (refetch the hole), then merge
    end
    C->>C: lastSeq = highest contiguous seq displayed
    Note over C: If the missed range is larger than one page, loop until lastSeq is reached (S13)
```

What to notice
- The broker is not involved in recovery. The database is the source of truth.
- Overlap between REST and live events is harmless. A gap is not.

---

## 5. Origin pod dies after the commit, before the ACK

Checks: S10. Decisions: D4, D9, D16. Customer P sent c-1. Pod 1 died after chat-service committed, so P never saw the ACK. The customer's laptop L stays connected on pod 3.

```mermaid
sequenceDiagram
    autonumber
    participant P as Customer P (phone)
    participant G1 as Gateway pod 1 (dies)
    participant CS as chat-service
    participant DB as MariaDB
    participant R as Outbox relay
    participant MQ as Artemis chat.events
    participant G3 as Gateway pod 3
    participant L as Customer L (laptop)
    participant G4 as Gateway pod 4

    P->>G1: WS send (clientMsgId c-1)
    G1->>CS: POST /internal/messages
    CS->>DB: BEGIN, insert message seq 18, insert outbox, COMMIT
    Note over G1: Pod 1 crashes before the ACK reaches P
    CS-->>G1: 201 (lost, nobody is listening)
    P->>P: Socket drops, bubble still sending, no ACK

    R->>MQ: Publish message.created (within about one poll interval)
    MQ-->>G3: Copy into pod 3 queue
    G3->>L: Push full message (L is not the origin socket)
    L->>L: Shows the message at once

    P->>G4: Reconnect (backoff, ticket, ready)
    alt Path 1: re-send
        P->>G4: WS send again with the same clientMsgId c-1
        G4->>CS: POST /internal/messages
        CS->>DB: Existing row found for c-1
        CS-->>G4: 200 original messageId, seq 18
        G4-->>P: ACK, bubble becomes sent
    else Path 2: resume
        P->>CS: GET .../messages?afterSeq=17
        CS-->>P: seq 18 (with clientMsgId c-1)
        P->>P: Match c-1 to the pending bubble, bubble becomes sent
    end
```

What to notice
- Nothing in the delivery path depended on pod 1. The relay published from the database.
- Both recovery paths end in the same single message. Either one, or both, is safe.
- The new socket on pod 4 has a new `sessionId`, so it may also receive the full message. The client dedupes by `clientMsgId` and `messageId`.

---

## 6. Client re-sends an already stored message

Checks: S12. Decision: D9. The server has the message. The client does not know, so it sends again with the same `clientMsgId`.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant G as Gateway pod
    participant CS as chat-service
    participant DB as MariaDB

    C->>G: WS send (clientMsgId c-1)
    G->>CS: POST /internal/messages
    CS->>DB: Select by (conversationId, senderId, clientMsgId c-1)
    DB-->>CS: Existing row, messageId, seq 18
    Note over CS: No new messageId, no new seq, no new outbox row, no new event
    CS-->>G: 200 original messageId, seq 18
    G-->>C: ACK (clientMsgId c-1, messageId, seq 18)
    C->>C: Bubble becomes sent
```

What to notice
- A duplicate request returns the original and creates nothing.
- The recipient can therefore never get a second copy from a re-send.

---

## 7. Concurrent duplicate sends (S17)

Checks: S17. Decisions: D9, D16. Two requests carry the same `clientMsgId` and reach chat-service almost together: the original request still in flight after its pod died, plus the client's re-send. T1 and T2 are two transactions.

```mermaid
sequenceDiagram
    autonumber
    participant T1 as chat-service tx 1
    participant T2 as chat-service tx 2
    participant DB as MariaDB

    T1->>DB: Select by key - none found
    T2->>DB: Select by key - none found
    T1->>DB: UPDATE conversation last_seq + 1 (takes row lock)
    T2->>DB: UPDATE conversation last_seq + 1 (waits for the row lock)
    T1->>DB: INSERT message seq 18, INSERT outbox
    T1->>DB: COMMIT (lock released)
    DB-->>T2: Lock granted, last_seq now 19
    T2->>DB: INSERT message with the same key
    DB-->>T2: Duplicate-key violation
    T2->>DB: ROLLBACK (last_seq increment is undone, seq 19 is free again)
    T2->>DB: Select by key
    DB-->>T2: Existing row from T1, messageId, seq 18
    T2-->>T2: Return the existing messageId and seq 18 as a success
```

What to notice
- The unique key is the real guard. The initial "select by key" is only a fast path.
- The duplicate-key error must never reach the user as an error.
- The rollback releases the counter increment, so `seq` stays gap-free.

---

## 8. DB commit fails

Checks: S11. Decisions: D3, D4. Nothing was stored, so nothing may be published.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant G as Gateway pod
    participant CS as chat-service
    participant DB as MariaDB

    C->>G: WS send (clientMsgId c-1)
    G->>CS: POST /internal/messages
    CS->>DB: BEGIN, increment last_seq, INSERT message, INSERT outbox
    DB-->>CS: Error (or crash) before COMMIT
    Note over DB: Everything rolls back, no message row, no outbox row, no seq consumed
    CS-->>G: 5xx
    G-->>C: Error for c-1
    C->>C: Bubble shows failed, user may retry with the same clientMsgId
    C->>G: Retry (clientMsgId c-1)
    Note over CS,DB: Creates exactly one message, as in diagram 1
```

What to notice
- No phantom event: the outbox row is part of the same transaction.
- A retry is safe because the id is the same and nothing was stored.

---

## 9. Relay publishes, but fails to mark the row (duplicate publish)

Checks: duplicate handling. Decisions: D4, D8, D9. At-least-once in action: the broker got the event but the relay crashed before marking the row.

```mermaid
sequenceDiagram
    autonumber
    participant R as Outbox relay
    participant DB as MariaDB
    participant MQ as Artemis chat.events
    participant G as Gateway pods

    R->>DB: Select unpublished rows (eventId e-1) FOR UPDATE SKIP LOCKED
    R->>MQ: Publish e-1 (broker confirms)
    MQ-->>G: Event e-1 delivered to pods (first copy)
    Note over R: Relay crashes before it marks e-1 as published
    DB->>DB: Transaction rolled back, lock released, e-1 still unpublished
    R->>DB: After restart or failover, select unpublished rows - e-1 again
    R->>MQ: Publish e-1 again (same eventId)
    MQ-->>G: Event e-1 delivered again (second copy)
    G->>G: Clients dedupe by messageId and eventId, shown once
    R->>DB: Mark e-1 published, COMMIT
```

What to notice
- The window between "publish" and "mark" cannot be removed, because two systems are involved. So duplicates are expected and absorbed.
- A republish reuses the same `eventId`, which is what makes de-duplication possible (D16).
- The leader lock (ShedLock) keeps this to one relay at a time. `SKIP LOCKED` and the dedupe are the safety nets underneath (D8).
