# AMQ Chat Training: Master Roadmap

Spec: `docs/superpowers/specs/2026-10-09-amq-chat-training-design.md`

Each module gets its own detailed plan, written just before the module starts (what you learn changes the next plan). A module is done only when its gate (cross-questions and the pass criteria) is met. One module at a time.

| # | Module | Plan file | Status |
|---|---|---|---|
| 0 | Setup (Compose: Artemis 2.43.0, MariaDB, Redis) | `2026-10-09-module-00-setup.md` | **Done** (infra, smoke test, README); own-words note `notes/00-setup.md` still owed by the user |
| 1 | Messaging fundamentals | `2026-10-11-module-01-fundamentals.md` | **Paused by choice after Task 7** (Tasks 1-7 done: wrapper, Labs A-F). Left: Task 8 back-pressure/slow consumers, Task 9 transactions/redelivery, Task 10 notes + quiz gate, plus the `--max-consumers 1`/FQQN follow-up lab and a re-run on 2.43.0. Resume later. |
| 2 | Artemis deep dive (+ platform-team question list) | `...-module-02-artemis.md` | Not started |
| 3 | Kafka deep dive | `...-module-03-kafka.md` | Not started |
| 4 | AMQ vs Kafka decision exercise | `...-module-04-compare.md` | Not started |
| 5 | Hands-on AMQ lab | `...-module-05-amq-lab.md` | Not started |
| 6 | Chat design | `2026-10-12-module-06-chat-design.md` | **Plan written** (8 tasks: domain model, message schema, states, devices/presence, event envelope, bank constraints, failure walk-through, review gate) |
| 7 | chat-service + outbox (persist-before-publish) | `...-module-07-chat-service.md` | Not started |
| 8 | ws-gateway, Phase 1 broadcast, Phase 2 Redis | `...-module-08-gateway.md` | Not started |
| 9 | Delivery guarantees, reconnect, scaling, deploy drills | `...-module-09-resilience.md` | Not started |
| 9b | Offline push notifications (mocked FCM/APNs, multi-device) | `...-module-09b-notifications.md` | Not started |
| 10 | MFE wiring (React Module Federation) | `...-module-10-mfe.md` | Not started |
| 11 | Production readiness + 50K/1K capacity report | `...-module-11-capacity.md` | Not started |
| S | Happy-path slice (chat-service + ws-gateway + plain page, built early at the user's request) | `2026-10-12-happy-path-slice.md` | **Plan written**, not started; spec `specs/2026-10-12-happy-path-slice-design.md` |
| S2 | Failure drills on the slice (break it on purpose, verify the design, resource-release audit) | `2026-10-15-failure-drills.md` | **Plan written**, not started; results in `docs/failure-drills-results.md`. The next plan, "production implementation from the design documents", is written after the drills. |

Teaching loop per module: concept note, predict, run and observe, break it, cross-questions, gate.

Progress is also tracked in `CLAUDE.md` section 4 (current state and next action). Design work done alongside the modules (spec, decisions D1-D18, sequence diagrams) is recorded in `docs/`.
