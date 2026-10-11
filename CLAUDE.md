# CLAUDE.md: glint-chat training program

Read this file fully at the start of every session. It gives you the objective, how the user wants to work, the current state, and which documents to read. Do not make the user re-explain any of it.

## 1. Objective
A long-running, hands-on training program. The user wants to become **technically very strong** in messaging (Apache ActiveMQ Artemis, the upstream of Red Hat AMQ, compared with Kafka), WebSocket gateways, and resilient delivery. The vehicle is a real-time **bank chat between a relationship manager (RM) and a customer**: MFE (web and mobile) <-> WebSocket gateway pods <-> chat-service <-> Artemis, with MariaDB as the source of truth and Redis for presence.

The user will **apply this to a real production system afterwards**, so the lessons, decisions and pitfalls we document are a reusable guideline. Treat production concerns (security, compliance, shared-broker risk, operations) as first-class.

Key facts and assumptions:
- Production broker is **Red Hat AMQ (Artemis-based, lags upstream)**. A concrete question for the user's platform team is whether the existing production AMQ is suitable for this chat; the answer is to be earned with evidence in Module 11, not assumed.
- Scale: **50K registered users, 1K concurrent** (design target), roughly 25-33 msg/s average, peaks of 3-5x, about 4 gateway pods. Treat these as assumptions to validate.
- Local stack in Docker Compose: Artemis `2.44.0`, MariaDB `11.4`, Redis `7.4` (versions pinned on purpose). Java 21, Spring Boot for services; React with Module Federation for the MFEs (web and mobile for both RM and customer; mobile is simulated by a scripted client). FCM/APNs are **mocked** (`fake-push`).

## 2. How to work with this user (important)
- **Move slowly, one thing at a time, layer by layer.** Prepare one item, explain it, let the user ask questions, and only then move on.
- **Pause after every step** during execution and explain what was done and why. Explain every command, flag and file you add (the user asks "what does X mean" often; answer plainly, with the reason, not just the definition).
- **Never commit on your own.** Commit only when the user says "commit" (after they have reviewed and asked their questions). Follow the commit attribution guidance given in the session. Never commit `infra/.env` or `claude-resume.txt` (both git-ignored, local only).
- **Ask cross-questions at each stage** and wait for answers before moving on. Evaluate answers honestly: say what is right, what is wrong, and why. Refine partial answers. If the user does not know something, explain it from scratch with a concrete scenario or timeline, then ask again.
- **Predict, run, observe.** Before each lab the user writes a prediction; compare it with what happens. Give the user the commands to run themselves when it teaches more; they like to run and see.
- **Be honest about uncertainty.** Mark things that are not yet proven as "to verify" (with the module where they will be proven). Verify claims against the actual running stack when cheap (for example CLI flags, versions, behaviour) instead of answering from memory. If an earlier statement of yours was wrong, say so plainly and fix the documents (this has happened, for example the origin of a message is `originSessionId`, not `deviceId`).
- **Always connect concepts back to the chat design and to production reality** (bank constraints: audit, retention, PII, least privilege, shared broker). Raise platform-team questions when they arise (they accumulate in `docs/design-decisions.md`, D18).
- **Keep documents current as we decide things.** After a decision or correction, update the spec and `docs/design-decisions.md`, add a sequence diagram when a new flow is discussed, and update notes. Distinguish **Decided** from **To verify**.
- Do not start applications on the user's machine unasked (for example Docker Desktop). If Docker is not running, ask the user to start it.
- Writing style: concise, direct, tables where they help, no filler. Use "they/them" for anyone whose pronouns are unknown.
- Process skills: the project uses the Superpowers skills (brainstorming, writing-plans, executing-plans). Each module gets its own plan, written **just before the module starts**, using writing-plans, and executed **inline** (not with subagents) with the pause-and-review rules above.

## 3. Documents to read at the start of every session
Read these (they are the living record of what we have learned and decided). Do not rely on memory of earlier sessions.

1. `docs/superpowers/specs/2026-10-09-amq-chat-training-design.md`: the design spec. Decisions table, architecture, **persist-before-publish**, **Identifiers**, **Client sync protocol** with acceptance checks S1-S17, teaching format, and all modules 0 to 11 including 9b.
2. `docs/design-decisions.md`: numbered decisions D1-D17 with problem, decision, rejected alternatives, gotchas and status, plus D18 open decisions and the platform-team questions.
3. `docs/superpowers/plans/2026-10-09-master-roadmap.md`: module list and status. Then the plan for the **current module** (listed there).
4. `docs/sequence-diagrams.md`: Mermaid diagrams, one per use case; add new ones as flows are discussed.
5. `notes/`: the user's learning log and reference notes: `00-learning-log.md` (what we covered and corrections), `00-docker-commands.md` (commands and flags), `01-rest-endpoints.md` (REST endpoint proposal), and later notes per module (`NN-*.md`). The user's own-words note for a module (for example `notes/00-setup.md`, `notes/01-fundamentals.md`) is theirs to write; do not write it for them.
6. `README.md`: setup and everyday commands.

If these documents disagree with what the user says now, the user's latest decision wins; update the documents and tell them what changed.

## 4. Current state (update this section at the end of a working session)
- **Module 0 (setup): infrastructure done and committed.** Artemis 2.44.0, MariaDB 11.4, Redis 7.4 run healthy; `./tests/smoke.sh` ends with ALL PASS; README written. Open: the user's own-words note `notes/00-setup.md` (they said they understand it and moved on; do not push, but remind once when a natural moment arises).
- **Module 1 (messaging fundamentals): plan written** (`docs/superpowers/plans/2026-10-11-module-01-fundamentals.md`), **not started**. Next action: Task 1 (the `labs/art.sh` wrapper and baseline round trip), after the user answers the warm-up question (difference between `MESSAGES_ADDED` and `MESSAGE_COUNT`).
- Later modules (2 to 11, 9b) have no detailed plan yet; write each one just before starting it.

## 5. Core design in one page (details in the spec and decisions)
- **Broker is transport, MariaDB is truth** (D2). A message is delivered only after it is committed in MariaDB (D3).
- **Transactional outbox, no XA** (D4): chat-service writes the message and an outbox row in one DB transaction; a **relay in chat-service** (polling, `FOR UPDATE SKIP LOCKED`, one active relay via a leader lock such as ShedLock) publishes to Artemis. The gateway **never publishes** chat events (D5-D8). At-least-once with idempotent consumers everywhere (D9).
- **Gateway pods consume** from Artemis: one multicast address `chat.events`, one queue **per pod**; Phase 1 broadcast (every pod receives, the pod with the socket delivers); Phase 2 adds Redis presence for targeted routing (fully qualified queue name `chat.events::<podId>`, to verify) with **broadcast as the fallback**; Redis is also for online/offline and push decisions (D11, D14). OpenShift only chooses the pod per socket at connect time; it does not route messages.
- **Identifiers** (D16): `clientMsgId` UUID v4 from the client (idempotency, reused on every retry of the same message); `messageId` UUID v7 from chat-service via `uuid-creator` behind an `IdGenerator`; per-conversation gap-free `seq` assigned under a row lock; `senderId` in the unique key is the authenticated `userId` (never `sessionId`); origin detection uses `originSessionId`; `deviceId` per install; `sessionId` per connection.
- **Client sync protocol** (D17): open socket, wait for `ready`, buffer live events, fetch history by REST (`afterSeq`), merge by contiguous `seq`, dedupe by `messageId`, hold events ahead of a gap and refetch. Acceptance checks S1-S17 in the spec.
- **Conversations:** one customer and one RM; reassigning to another RM creates a new conversation.
- **Delivery states:** sending, sent (committed in MariaDB), delivered (recipient device app-ACK), read (`lastReadSeq`). An echo of your own event is not "delivered".
- **Offline push** is Module 9b: a separate notification-service consumer, content-free pushes, per-device presence, mocked providers.

## 6. Environment facts and gotchas
- macOS, zsh. Run commands from the repo root. Full compose form: `docker compose --env-file infra/.env -f infra/docker-compose.yml <cmd>` (optional `dc` alias, only valid from the repo root).
- `infra/.env` is local and git-ignored; `infra/.env.example` is the committed template. Redis needs its password (`REDIS_PASSWORD`); its port is bound to loopback.
- Artemis CLI inside the container: `/var/lib/artemis-instance/bin/artemis`; console http://localhost:8161. When piping command output, capture it into a variable before `grep -q` if `pipefail` is set (early-closing pipes cause false failures).
- If Docker commands fail with "cannot connect to the Docker API", Docker Desktop is not running: ask the user to start it.
- `claude-resume.txt` is the user's local file; do not read, edit or commit it.
- Date format and file naming: plans and specs use `YYYY-MM-DD-<topic>.md`.
