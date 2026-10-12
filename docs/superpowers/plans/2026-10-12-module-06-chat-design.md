# Module 6: Chat Design Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline, with a pause after every task, as the user requires). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Settle every design decision that Module 7 (chat-service and outbox) needs, and write it down in one design document the user can defend line by line.

**Architecture:** This is a design module. Each task takes one topic: the user answers questions first, Claude presents a proposal with trade-offs, the user decides, and the decision is recorded in `docs/chat-design.md` and `docs/design-decisions.md`. No service code is written here; the MariaDB schema is drafted as SQL in the design document and turned into migrations in Module 7.

**Tech Stack:** MariaDB 11.4 (schema draft), Artemis 2.43.0 (event routing), Redis 7.4 (presence), Mermaid (diagrams). Java 21 / Spring Boot is Module 7.

## Global Constraints

- Broker is transport, MariaDB is truth (D2). A message is published only after it is committed (D3). Transactional outbox, no XA (D4).
- One customer and **one or more RMs** per conversation (user decision, 2026-10-12): it starts with one RM and more RMs can join later; membership changes are recorded events with a `seq`. `seq` stays per conversation.
- Identifiers as in the spec "Identifiers" section: `messageId` and `eventId` UUID v7, `clientMsgId` UUID v4, `seq` per conversation gap-free and assigned under a row lock on `conversation.last_seq`, `senderId` is the authenticated `userId`, unique key `(conversationId, senderId, clientMsgId)`.
- Model multiple devices per user and multiple recipients per message from the start; delivery state per `(messageId, userId)`, read state per `(conversationId, userId)` as `lastReadSeq`.
- The WebSocket and REST client contract with versioning is **deferred** (D18 action item). Message `type` is an open string; the content shape is not designed here.
- Production concerns are first-class: audit, retention, PII, least privilege, shared broker. Distinguish **Decided** from **To verify**, and add platform or compliance questions to D18.
- The user decides; Claude proposes. The user's own-words note is `notes/06-chat-design.md` and is not written by Claude.
- Never commit until the user says "commit".

## File Structure

- Create: `docs/chat-design.md` is the single design document for this module: domain model, schema draft, states, presence, event envelope, bank constraints, acceptance-check mapping. Sections are added one task at a time.
- Modify: `docs/design-decisions.md` adds D19 onward for each new decision, and closes the matching D18 items.
- Modify: `docs/sequence-diagrams.md` when a decision changes a flow or state diagram.
- Modify: `docs/superpowers/plans/2026-10-09-master-roadmap.md` for the module status.
- Not touched: `notes/06-chat-design.md` is the user's.

---

### Task 1: Domain model and conversation lifecycle

> **Status 2026-10-12:** questions answered and recorded in `docs/chat-design.md` section 1 and D19. The address and event envelope (Task 5) were designed first at the user's request; see section 5. Remaining: the Task 1 cross-questions below.

**Files:**
- Create: `docs/chat-design.md` (section 1)
- Modify: `docs/design-decisions.md` (D18 reassignment item)

**Interfaces:**
- Produces: the entities `conversation` and `conversation_participant`, the conversation statuses, and the rules for who can start, close and reassign. Task 2 relies on `conversation.last_seq`.

- [ ] **Step 1: Ask the user** (one at a time, wait for each answer)
  1. Who can start a conversation: only the customer, only the RM, or both? Can a customer message an RM they are not assigned to?
  2. When a new RM joins: can they see the messages from before they joined (full history, only from the join point, or decided by whoever adds them)? Who is allowed to add or remove an RM (supervisor, an existing RM, a bank system)?
  2b. Where does the customer-to-RM assignment come from: another bank system we only read, or does chat-service own it? What happens when that system changes the assignment?
  3. Can a conversation be closed or archived, and by whom? Can it be reopened?
  4. Can a supervisor or compliance officer read a conversation they are not part of? If yes, as a participant or through a separate path?

- [ ] **Step 2: Claude presents the starting proposal** (the user may change any line):

```sql
CREATE TABLE conversation (
  id          BINARY(16)  NOT NULL,                         -- conversationId, UUID v7
  status      ENUM('ACTIVE','CLOSED') NOT NULL DEFAULT 'ACTIVE',
  last_seq    BIGINT      NOT NULL DEFAULT 0,               -- locked and incremented per message
  created_at  DATETIME(3) NOT NULL,
  closed_at   DATETIME(3) NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

CREATE TABLE conversation_participant (
  conversation_id BINARY(16)  NOT NULL,
  user_id         VARCHAR(64) NOT NULL,                     -- from the identity provider
  role            ENUM('CUSTOMER','RM') NOT NULL,
  last_read_seq   BIGINT      NOT NULL DEFAULT 0,           -- read cursor (D18 item 3 may extend this)
  joined_at       DATETIME(3) NOT NULL,
  left_at         DATETIME(3) NULL,                         -- set when an RM is replaced
  PRIMARY KEY (conversation_id, user_id),
  KEY idx_user (user_id, conversation_id)
) ENGINE=InnoDB;
```
Rules proposed: exactly one customer and at least one active RM per conversation (enforced in the application, with a test); an RM joins or leaves by changing `joined_at` / `left_at` and writing a system event (with a `seq`) into the conversation in the same transaction; each participant has a `visible_from_seq` (added in Step 3 once the history-visibility question is answered).

- [ ] **Step 3: User decides.** Record the answers and the final model in `docs/chat-design.md` section 1 and as D19 in `docs/design-decisions.md`.

- [ ] **Step 4: Cross-questions**
  1. Why is `last_seq` kept on `conversation` and not computed as `MAX(seq)` from the message table? What goes wrong with `MAX(seq)+1` when two requests arrive together?
  2. A third RM joins a conversation with 200 messages. What does their client download, and what does `lastSeenSeq` start at?
  3. A message has three recipients (customer, RM A, RM B). What must "delivered" and "read" mean for the sender? (This reopens Task 3.)

Pause for review.

---

### Task 2: Message schema and the idempotent insert

**Files:**
- Modify: `docs/chat-design.md` (section 2)
- Modify: `docs/design-decisions.md` (D20)

**Interfaces:**
- Consumes: `conversation.last_seq` from Task 1.
- Produces: the `message` and `outbox` tables and the insert transaction that Module 7 implements.

- [ ] **Step 1: Ask the user**
  1. Can a sent message be edited or deleted? What does the bank's audit and compliance rule say?
  2. What is the maximum message size, and are attachments stored in MariaDB or elsewhere (object storage, only a reference in the table)?
  3. Should the stored message contain the plain text fallback (see the deferred contract) or only the rich content?

- [ ] **Step 2: Claude presents the starting proposal**

```sql
CREATE TABLE message (
  id              BINARY(16)   NOT NULL,                    -- messageId, UUID v7
  conversation_id BINARY(16)   NOT NULL,
  seq             BIGINT       NOT NULL,
  sender_id       VARCHAR(64)  NOT NULL,                    -- authenticated userId
  client_msg_id   BINARY(16)   NOT NULL,                    -- UUID v4 from the device
  type            VARCHAR(32)  NOT NULL,                    -- open string, 'text' first
  body            MEDIUMTEXT   NOT NULL,                    -- shape decided with the contract
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_conversation_seq (conversation_id, seq),
  UNIQUE KEY uq_idempotency (conversation_id, sender_id, client_msg_id)
) ENGINE=InnoDB;

CREATE TABLE outbox (
  id              BIGINT       NOT NULL AUTO_INCREMENT,     -- outboxId, relay cursor, internal only
  event_id        BINARY(16)   NOT NULL,                    -- UUID v7
  event_type      VARCHAR(40)  NOT NULL,                    -- message.created, ...
  conversation_id BINARY(16)   NOT NULL,
  payload         MEDIUMTEXT   NOT NULL,                    -- the event envelope as JSON (Task 5)
  trace_id        VARCHAR(64)  NULL,
  created_at      DATETIME(3)  NOT NULL,
  published_at    DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_event (event_id),
  KEY idx_unpublished (published_at, id)
) ENGINE=InnoDB;
```
The insert transaction (one DB transaction, in this order):
1. `SELECT last_seq FROM conversation WHERE id = ? FOR UPDATE`.
2. Try the idempotent insert; if the unique key `uq_idempotency` already exists, return the stored `messageId` and `seq` and stop.
3. Set `seq = last_seq + 1`, update `conversation.last_seq`, insert into `message`, insert the `message.created` row into `outbox`.
4. `COMMIT`, then return the ACK (`clientMsgId`, `messageId`, `seq`).

- [ ] **Step 3: User decides.** Record the final tables and the transaction in `docs/chat-design.md` section 2 and as D20.

- [ ] **Step 4: Cross-questions**
  1. Two requests for the same conversation arrive at once. Walk through the transaction: who waits, who gets which `seq`?
  2. The same `clientMsgId` arrives twice concurrently (acceptance check S17). Which statement makes the second one return the first one's result, and what does the first request's step 1 lock do to it?
  3. Why is `outbox.id` AUTO_INCREMENT but `message.seq` is not?
  4. What breaks if step 1 (lock) and step 2 (idempotency check) are swapped?

Pause for review.

---

### Task 3: Message states, delivery and read (closes the D18 state questions)

**Files:**
- Modify: `docs/chat-design.md` (section 3)
- Modify: `docs/design-decisions.md` (D21; close the four D18 state items)
- Modify: `docs/sequence-diagrams.md` (diagram 10a if the states change)

**Interfaces:**
- Consumes: `message` from Task 2, `conversation_participant.last_read_seq` from Task 1.
- Produces: the delivery table, the rule for several devices, the failure state, and the read evidence rule.

- [ ] **Step 1: Ask the user** the four open questions from D18, one at a time:
  1. **Delivered with several devices:** the first device that acknowledges, or all of them? (Proposal: first device, with `deviceId` recorded.)
  2. **A failure state:** what should the sender see when a message can never be delivered (for example, the RM account was deactivated)? Today it stays "sent" forever.
  3. **Read evidence:** does compliance need to prove that a specific message was read, or is the `lastReadSeq` cursor enough?
  4. **Offline indicator:** should the sender see "recipient offline" (this would come from presence)? Is that wanted for a bank, or a privacy problem?

- [ ] **Step 2: Claude presents the starting proposal**

```sql
CREATE TABLE message_delivery (
  message_id   BINARY(16)  NOT NULL,
  user_id      VARCHAR(64) NOT NULL,                        -- recipient
  device_id    BINARY(16)  NOT NULL,                        -- first device that acknowledged
  delivered_at DATETIME(3) NOT NULL,
  PRIMARY KEY (message_id, user_id)
) ENGINE=InnoDB;
```
Read state stays `conversation_participant.last_read_seq`. State changes are events (`message.delivered`, `message.read`) written to the outbox in the same transaction as the table update.

- [ ] **Step 3: User decides.** Update diagram 10a if needed; record in `docs/chat-design.md` section 3 and D21; mark the four items in D18 as decided.

- [ ] **Step 4: Cross-questions**
  1. Why is "delivered" a per-recipient, per-message fact but "read" a cursor?
  2. The recipient's device acknowledges delivery twice (a retry). What makes this harmless?
  3. A read cursor says `lastReadSeq = 50`. Can the RM claim the customer read message 30 at a specific time? What would a bank need to change?

Pause for review.

---

### Task 4: Devices, presence and offline handling

**Files:**
- Modify: `docs/chat-design.md` (section 4)
- Modify: `docs/design-decisions.md` (D22)

**Interfaces:**
- Consumes: the presence shape in D14 (`presence:{userId}`, field `{sessionId}`, value `{podId}|{deviceId}|{platform}`).
- Produces: the device registry table and the offline rules that Module 9b uses.

- [ ] **Step 1: Ask the user**
  1. How many devices can a user register? Can an RM be logged in on two browsers and a phone at once?
  2. When a user logs out or a token is revoked, what happens to their open sockets and their push tokens?
  3. "Offline" means what for the product: no socket, app in background, or no activity for N minutes?

- [ ] **Step 2: Claude presents the starting proposal**

```sql
CREATE TABLE device (
  id           BINARY(16)   NOT NULL,                       -- deviceId, UUID v4 from the client
  user_id      VARCHAR(64)  NOT NULL,                       -- bound server-side to the authenticated user
  platform     ENUM('WEB','IOS','ANDROID') NOT NULL,
  push_token   VARCHAR(512) NULL,                           -- rotates; removed on "invalid token"
  last_seen_at DATETIME(3)  NOT NULL,
  active       TINYINT(1)   NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  KEY idx_user (user_id, active)
) ENGINE=InnoDB;
```
Rules proposed: presence in Redis is a hint with TTL and heartbeat; "unknown is not offline" (when Redis is unavailable, delay the push or use the delivery-ACK rule); the pod's local socket map is authoritative; the push decision is per device.

- [ ] **Step 3: User decides.** Record in `docs/chat-design.md` section 4 and D22.

- [ ] **Step 4: Cross-questions**
  1. Redis restarts and presence is empty. What does the notification-service do for the next 30 seconds, and why?
  2. A device is stolen. List every place a token or session must be invalidated.

Pause for review.

---

### Task 5: Addressing and the internal event envelope

**Files:**
- Modify: `docs/chat-design.md` (section 5)
- Modify: `docs/design-decisions.md` (D23)
- Modify: `docs/sequence-diagrams.md` (only if the routing story changes)

**Interfaces:**
- Consumes: D14 (one multicast address `chat.events`, one queue per pod), the `outbox.payload` column from Task 2.
- Produces: the event envelope (JSON) that chat-service publishes and the gateway pods and the notification-service consume.

- [ ] **Step 1: Ask the user**
  1. We chose one multicast address with one queue per pod (D14) over per-conversation or per-user addresses. State in your own words why, in three sentences.
  2. What must a gateway pod know from an event to decide whether it holds a recipient and to deliver it? List the fields.

- [ ] **Step 2: Claude presents the starting proposal**

```json
{
  "eventId": "uuid-v7",
  "eventType": "message.created",
  "occurredAt": "2026-10-12T10:15:30.123Z",
  "traceId": "uuid",
  "conversationId": "uuid-v7",
  "participants": ["customerUserId", "rmUserId"],
  "originSessionId": "uuid-v4 or null",
  "message": {
    "messageId": "uuid-v7",
    "seq": 42,
    "senderId": "rmUserId",
    "clientMsgId": "uuid-v4",
    "type": "text",
    "createdAt": "2026-10-12T10:15:30.120Z"
  }
}
```
Open for the user: whether the event carries the message body, or only identifiers so that the gateway fetches the body (privacy and size trade-off), and whether the envelope is versioned with a `schemaVersion` field. Consumers must ignore unknown fields (the deferred contract's rule applies to the internal event too).

- [ ] **Step 3: User decides.** Record the envelope in `docs/chat-design.md` section 5 and D23.

- [ ] **Step 4: Cross-questions**
  1. Why is `originSessionId` in the event and not `deviceId`?
  2. A duplicate event arrives at a pod. Which field makes it harmless?
  3. If the body is not in the event, what does that do to the read path, the log content and the failure modes?

Pause for review.

---

### Task 6: Bank constraints

**Files:**
- Modify: `docs/chat-design.md` (section 6)
- Modify: `docs/design-decisions.md` (D24 and the platform and compliance questions in D18)

**Interfaces:**
- Produces: a list of non-functional rules every later module must respect, each marked Decided or To verify with the owner of the answer.

- [ ] **Step 1: Ask the user** (these answers may need the bank's compliance team; record "unknown, ask X" if so)
  1. **Retention:** how long must chat be kept? Can it be deleted, and what happens at the end of the period?
  2. **Audit and compliance recording:** is every message recorded for surveillance? Where does that copy live and who reads it?
  3. **PII and logging:** may message content or names appear in logs, traces, broker messages or push payloads?
  4. **Access:** which roles can read which conversations (RM, supervisor, compliance, support)? Is access to a conversation itself audited?
  5. **Encryption:** in transit (TLS everywhere including the broker) and at rest (database, backups, the broker journal)?
  6. **Data residency and legal hold:** any restrictions?

- [ ] **Step 2: Claude presents the starting proposal.** Defaults: content never in logs; push payloads contain no content; the broker journal holds message content only if the event carries the body, so this feeds the Task 5 decision; access checked in chat-service on every read and write; audit of reads of other users' conversations; soft retention rules implemented as a scheduled job in a later module.

- [ ] **Step 3: User decides.** Record decided rules and the open questions with an owner and a due date in `docs/chat-design.md` section 6, D24 and D18.

- [ ] **Step 4: Cross-questions**
  1. Where would message content leak by accident in this design? List five places (logs, traces, broker, dead-letter queue, push, backups) and the defense for each.
  2. The shared production AMQ is used by other teams. What does that imply about what we put in events?

Pause for review.

---

### Task 7: Walk the failures and map the acceptance checks

**Files:**
- Modify: `docs/chat-design.md` (section 7)
- Modify: `docs/superpowers/specs/2026-10-09-amq-chat-training-design.md` (add S18 and S19 if the user accepts the heartbeat and conversation-list proposals)

**Interfaces:**
- Consumes: sections 1 to 6 of `docs/chat-design.md` and diagrams 1 to 10 in `docs/sequence-diagrams.md`.
- Produces: a table mapping each acceptance check (S1 to S19) to the table, column or rule that makes it testable. This table is the test list for Module 7.

- [ ] **Step 1: Claude walks each sequence diagram against the schema** and lists any step the schema cannot support. Fix the design, not the diagram.

- [ ] **Step 2: Ask the user to decide the two proposals** from D18: the heartbeat carrying the latest `seq` per conversation (S18), and the conversation-list refresh and version (S19).

- [ ] **Step 3: Write the mapping table** in `docs/chat-design.md` section 7 (check, mechanism, table or rule, which module tests it).

- [ ] **Step 4: Cross-questions**
  1. Pick three checks. For each, say what test in Module 7 would fail if the mechanism were removed.

Pause for review.

---

### Task 8: Design review gate

**Files:**
- Modify: `docs/chat-design.md` (final read-through)
- Modify: `docs/superpowers/plans/2026-10-09-master-roadmap.md` (status)
- Modify: `CLAUDE.md` (current state)

- [ ] **Step 1: Claude reads `docs/chat-design.md` as a reviewer** and lists contradictions with the spec, D1 to D24 or the diagrams. Fix them.

- [ ] **Step 2: The user defends the design aloud** (in chat): pick any five decisions; for each, state the problem, the choice, the best rejected alternative and where the choice would fail. Claude challenges each in the blunt style the user asked for.

- [ ] **Step 3: Pass criteria.** The user can explain the insert transaction, the state model, the event envelope and the bank constraints without notes, and every open item has an owner. Then mark Module 6 done in the roadmap and update `CLAUDE.md` section 4.

- [ ] **Step 4: Remind the user** that `notes/06-chat-design.md` (their own words) is theirs to write.

Wait for the user to say "commit" before committing anything.
