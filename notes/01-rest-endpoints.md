# REST endpoints besides the WebSocket (proposal, for review)

The MFE talks to the gateway pods (the edge). The WebSocket carries live events; everything that is a request/response, a bulk read, or must work when no socket is open goes over REST. The gateway authenticates, then forwards to chat-service, which owns the data (see design decision D5). Paths and fields are a first draft; refine in Modules 6 and 7.

Conventions: every request carries the user's token (`userId` comes from the token, never from the body). `{id}` values are UUIDs. List endpoints are paginated.

## A. Edge endpoints (MFE to gateway pod)

### Session and sockets
| Method and path | Purpose |
|---|---|
| `POST /ws/ticket` | Returns a short-lived, single-use ticket for opening a WebSocket. Avoids putting the long-lived token in the URL. Body: `deviceId`. |
| `GET /health/live`, `GET /health/ready` | Kubernetes probes. `ready` is false while the pod is draining (graceful shutdown). |

### Conversations
| Method and path | Purpose |
|---|---|
| `GET /conversations` | The caller's conversations, with last message preview, `lastSeq`, unread count, and counterpart info. Paginated. |
| `GET /conversations/{conversationId}` | Details and participants. Checks the caller is a participant. |
| `POST /conversations` | Create a conversation (for example the customer starts a chat; the RM is assigned by the assignment rule). Body includes a client request id for idempotency. |
| `POST /conversations/{id}/transfer` | Reassign the customer to another RM (supervisor role). Closes this conversation and creates a new one with the new RM (new `conversationId`, `seq` from 1); returns the new id. Idempotent on a request id. History visibility for the new RM is a Module 6 policy. |
| `POST /conversations/{id}/close` | Close a conversation (status, not delete; retention applies). |

### Messages and sync
| Method and path | Purpose |
|---|---|
| `GET /conversations/{id}/messages?limit=N` | First load on a new device: the last N messages. |
| `GET /conversations/{id}/messages?afterSeq=S&limit=N` | Resume and gap fill: messages after `seq` S, ascending. Loop until the page is not full. |
| `GET /conversations/{id}/messages?beforeSeq=S&limit=N` | Scroll back through older history. |
| `POST /conversations/{id}/messages` | Send a message over REST, as a fallback when the socket is down, or for clients that prefer it. Body: `clientMsgId`, `body`. Idempotent on `(conversationId, senderId, clientMsgId)`; returns `messageId` and `seq`. Primary path stays the socket. |
| `POST /conversations/{id}/read` | Mark read up to `seq` S (sets `lastReadSeq`). Body: `upToSeq`. Idempotent and monotonic (never moves backwards). |
| `POST /conversations/{id}/delivered` | Fallback for reporting delivery receipts when the socket is unavailable. Normally sent as a socket frame. |

### Unread and presence (optional, later)
| Method and path | Purpose |
|---|---|
| `GET /unread-summary` | Total unread across conversations (badge counts) without loading each conversation. |
| `GET /users/{userId}/presence` | Online/offline for the counterpart (from Redis, Phase 2). Respect privacy: only for counterparts in a shared conversation. |

### Devices (Module 9b)
| Method and path | Purpose |
|---|---|
| `PUT /devices/{deviceId}` | Register or update a device: `platform` (web, android, ios), `pushToken`. Called on app start and when the token rotates. |
| `DELETE /devices/{deviceId}` | Unregister on logout, so no pushes go to a logged-out device. |

## B. Internal endpoints (gateway pod to chat-service)
Not exposed to the MFE. Authenticated service-to-service.

| Method and path | Purpose |
|---|---|
| `POST /internal/messages` | The persist call from the gateway. Body: `conversationId`, `senderId`, `originSessionId`, `clientMsgId`, `body`, `traceId`. Returns `messageId`, `seq` only after the DB commit. |
| `GET /internal/conversations/{id}/participants` | Used by the gateway to verify membership when a socket subscribes to a conversation (never trust the client). |
| `POST /internal/receipts` | Delivered/read reports coming from clients, persisted and turned into status events. |
| The read endpoints in section A | The gateway proxies these to chat-service. |

## C. Not REST
- Live message push, typing indicators, presence changes, delivery/read status events to the sender: WebSocket frames.
- The `ready` frame after a socket is registered (the client waits for it before starting its history fetch).
- Heartbeats (ping/pong).

## Open questions
- Whether resume replay should also be offered over the socket handshake (server-side replay) in addition to REST. Compare in Module 9.
- Rate limits per user and per endpoint (protect `POST .../messages` and history fetches).
- Maximum page size and body size limits.
- Whether the MFE should call chat-service directly through the API gateway for reads, or always through the pod. Current draft: through the pod, for a single edge.
