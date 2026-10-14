# Chat design (Module 6)

The single design document for the chat. Each section is added when its Module 6 task is finished and the user has decided. Status of every line: **Decided** (by the user), **Proposed** (Claude's proposal, not yet confirmed) or **To verify** (needs a lab).

## 1. Domain model and conversation lifecycle (Task 1, decided)

Decided (user, 2026-10-12):
- A conversation has one customer and **one or more RMs**. It starts with one RM; more RMs can join later. (Supersedes the earlier "one RM, reassignment creates a new conversation" rule.)
- Either the customer or an RM can start a conversation, only between a customer and an RM linked by the bank's assignment records. The bank's assignment system is the source of truth; chat-service reads it (open detail in D18).
- A joining RM's history visibility is chosen by whoever adds them (full history or from the join point). The safe default is from the join point; full history needs an explicit flag; the choice and who made it is a recorded system event. The server enforces `visible_from_seq` on every history fetch.
- Any RM already in the conversation can add a colleague. Controls: only users with the RM role can be added; audit record (who added whom, history mode); rate limit; the join is visible to all participants, including the customer. The customer is only informed and cannot object in the product (compliance question open in D18).
- An RM can only remove themselves. Proposed (to confirm): an identity check on every request (a deactivated account cannot read or write even if still listed) and an audited administrative removal path for incidents.
- Access after an RM leaves is decided by bank policy; default none; both values stored.
- Conversations are **never closed**. One long thread. A "close chat" button is a UI action only.
- Retention: the customer's history is **hidden from the customer's view** after a period; nothing is deleted from the database; the RM's period is longer or unlimited. A visibility rule per role. Open: legal retention period, a new acceptance check S20 (`earliestAvailableSeq`, so retention is not mistaken for a gap), table growth and archiving (Module 11).
- Only participants can read a conversation in the product; there is no supervisor read path. A separate compliance and investigation path must read MariaDB, not the broker (open in D18).
- ~~Proposed: at most one conversation per customer and first RM.~~ Dropped 2026-10-15 with the AI persona (an AI-first conversation has no RM at creation). "One thread per customer or per topic" is open in D18; the slice's seed creates exactly one conversation.

### Schema draft (proposal built from the decisions above; finalized with Task 2)
```sql
CREATE TABLE conversation (
  id           BINARY(16)   NOT NULL,                        -- conversationId, UUID v7
  customer_id  VARCHAR(64)  NOT NULL,
  last_seq     BIGINT       NOT NULL DEFAULT 0,              -- locked and incremented per message
  created_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_customer (customer_id)
) ENGINE=InnoDB;
-- Persona-neutral on purpose (2026-10-15): no RM column here. Who is in the chat, including a
-- future AI participant, lives only in conversation_participant (see D18, "AI persona").

CREATE TABLE conversation_participant (
  conversation_id    BINARY(16)   NOT NULL,
  user_id            VARCHAR(64)  NOT NULL,
  role               ENUM('CUSTOMER','RM') NOT NULL,
  added_by           VARCHAR(64)  NULL,                      -- who added this RM
  history_mode       ENUM('FULL','FROM_JOIN') NOT NULL DEFAULT 'FROM_JOIN',
  visible_from_seq   BIGINT       NOT NULL DEFAULT 1,        -- server-enforced on every history fetch
  last_read_seq      BIGINT       NOT NULL DEFAULT 0,
  joined_at          DATETIME(3)  NOT NULL,
  left_at            DATETIME(3)  NULL,
  can_read_after_leave TINYINT(1) NOT NULL DEFAULT 0,        -- bank policy sets it; default none
  PRIMARY KEY (conversation_id, user_id),
  KEY idx_user (user_id, conversation_id)
) ENGINE=InnoDB;
```
Joins and leaves are **system events stored as rows in `message`** with a type such as `system.membership.joined`, so they get a `seq`, appear in history and reach every client in order. Retention windows per role are configuration, not columns: a message is visible to a participant if `seq >= visible_from_seq` and `created_at >= now - retention(role)`.

## 5. The `chat.events` address (in progress; designed first at the user's request)

Decided (user, 2026-10-12):
- **One multicast address `chat.events`** for all persisted chat events. `eventType` is a message property. The name pattern is `chat.<class>`; `chat.signals` is reserved for ephemeral signals (typing, presence) and is not designed yet.
- Created **multicast-only** (no anycast); publishers use `topic://`.
- Address-full policy **`FAIL`**: the relay gets an error, the outbox row stays unpublished and is retried, and the failure can be alerted on.
- Message **TTL 60 seconds** on the address (starting value, to tune in Module 9): an event older than that in a pod queue means the pod is stuck and its users have already resynced.

- **Queue per gateway pod: non-durable, named** `chat.events::<podId>`, `max-consumers 1`, deleted when its connection closes (no orphans by construction), created and attached before the pod reports ready, recreated after a connection loss. Durability buys nothing because events are rebuildable from MariaDB. **Verified in the slice (2026-10-15, Task 7, Artemis 2.43.0 in Testcontainers):** JMS cannot create it (`ActiveMQSession.createQueue` takes only a name; `QueueConfiguration` is not accepted). The queue is created with the **Artemis Core API** (`ClientSession.createQueue(QueueConfiguration)`, using the JMS factory's `getServerLocator()`), then consumed through JMS/Spring as `chat.events::<podId>`. Proven by tests: a message published to the topic reaches the pod queue; two pod queues each get a copy; `max-consumers 1` rejects a second consumer; creating twice is tolerated via `ActiveMQQueueExistsException`. **Not implemented yet: re-creating the queue after a connection loss** (it is created once at startup; see D18, Modules 8 and 9). Still to verify: that the broker deletes the queue after the pod's connection closes (observed by hand in Task 9, not asserted), and the permission to create queues with these attributes on the shared broker (platform team).
- **A second address `chat.notifications`** for the notification-service, with its own settings (durable queue, persistent messages, no short TTL). Reason: a multicast message's TTL applies to every queue copy, so the 60-second TTL on `chat.events` would expire the notification events during a deploy. The relay publishes each event to both addresses and marks the outbox row published only after both sends succeed; duplicates are harmless because of `eventId` dedupe. To verify: whether a per-queue TTL exists (it would make the second address unnecessary).

- **Event envelope carries the message body** on `chat.events` (user decision, against the privacy-first proposal). Controls: `chat.notifications` gets a **content-free** envelope (ids only), because it is durable and persistent and would be journaled; expired messages on `chat.events` are dropped, not moved to the default `ExpiryQueue`; no body in any dead-letter queue we do not control; never log the body, log ids; a maximum event size, attachments by reference only. To verify (Module 8): that messages in non-durable queues are not journaled, and how to configure drop-on-expiry for one address.

- **Participants are a snapshot taken at commit**, stored in the outbox payload; the relay publishes it unchanged. A leave that follows a commit by seconds leaves a short race (the leaving RM still gets that event); closing it is a Task 7 decision.
- **`schemaVersion`** (integer, starts at 1) in every envelope. Additive changes keep the version; breaking changes bump it. Consumers ignore unknown fields and drop or park events with an unknown version. Roll out consumers first, publishers second.

### The envelope (Decided shape; field names may still change in Module 7)

On `chat.events` (to gateway pods), full:
```json
{
  "schemaVersion": 1,
  "eventId": "uuid-v7",
  "eventType": "message.created",
  "occurredAt": "2026-10-12T10:15:30.123Z",
  "traceId": "uuid",
  "conversationId": "uuid-v7",
  "participants": ["customerUserId", "rmUserId1", "rmUserId2"],
  "originSessionId": "uuid-v4 or null",
  "message": {
    "messageId": "uuid-v7",
    "seq": 42,
    "senderId": "rmUserId1",
    "clientMsgId": "uuid-v4",
    "type": "text",
    "body": "...",
    "createdAt": "2026-10-12T10:15:30.120Z"
  }
}
```
On `chat.notifications` (to the notification-service): the same envelope **without** `message.body`.

Other event types that use the same envelope (their `message`/payload block is designed in Task 3): `message.delivered`, `message.read`, `membership.joined`, `membership.left`.

Broker message properties set by the relay on every message: `eventType`, `conversationId`, `eventId`. Proposed: send `chat.events` messages as **non-persistent** (the per-pod queues are non-durable, so persistence buys nothing) and `chat.notifications` messages as **persistent**. To verify in Module 7/8: whether using `eventId` as the broker's duplicate-detection id (`_AMQ_DUPL_ID`, from the Artemis docs, not yet tested) can absorb duplicate publishes at the broker, as a second line behind consumer dedupe.

Proposed (not yet confirmed): a slow-consumer policy that disconnects consumers that cannot keep up; alerts on queue depth and oldest message age per pod; address-setting names and behavior to verify on 2.43.0 (Module 8).

To verify / platform question: on the shared production AMQ, who sets the address limits and can they differ per address? (Added to D18.)
