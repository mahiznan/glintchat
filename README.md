# glint-chat

A training project: a real-time chat between a bank relationship manager (RM) and a customer, built layer by layer to learn messaging (Apache ActiveMQ Artemis, the upstream of Red Hat AMQ, compared with Kafka), WebSocket gateways, and resilient delivery.

Design and decisions
- Spec: `docs/superpowers/specs/2026-10-09-amq-chat-training-design.md`
- Roadmap and plans: `docs/superpowers/plans/`
- Design decisions: `docs/design-decisions.md`
- Sequence diagrams (Mermaid): `docs/sequence-diagrams.md`
- Learning notes: `notes/`

## Prerequisites
- Docker with Docker Compose v2 or later (the Docker daemon must be running)
- Java 21 and Node (needed from Module 7 onwards, not for the infrastructure)
- `curl` (used by the smoke test)

## First-time setup
1. Create your local environment file from the template and edit the placeholder values if you wish:
   ```bash
   cp infra/.env.example infra/.env
   ```
   `infra/.env` is git-ignored and must never be committed. `infra/.env.example` is the template and holds placeholders only.

2. Start the stack and wait until every service is healthy:
   ```bash
   docker compose --env-file infra/.env -f infra/docker-compose.yml up -d --wait
   ```

3. Verify:
   ```bash
   ./tests/smoke.sh
   ```
   Expected: a `PASS` line per check, then `ALL PASS`.

## What runs

| Service | Image | Host port | Purpose |
|---|---|---|---|
| artemis | `apache/activemq-artemis:2.43.0` | 61616 (broker), 8161 (web console) | Message broker |
| mariadb | `mariadb:11.4` | 3306 | Source of truth for chat data |
| redis | `redis:7.4` | 6379 (loopback only) | Presence and cache, rebuildable, persistence off |

- Artemis console: http://localhost:8161 (user and password from `infra/.env`)
- MariaDB: database, user and password from `infra/.env`
- Redis: password from `infra/.env`; the port is published on `127.0.0.1` only

## Everyday commands
The commands are written in full so they work anywhere in the repo root. An optional shortcut for your shell session:
```bash
alias dc='docker compose --env-file infra/.env -f infra/docker-compose.yml'
```

| Goal | Command |
|---|---|
| Start and wait until healthy | `docker compose --env-file infra/.env -f infra/docker-compose.yml up -d --wait` |
| Status and health | `... ps` |
| Logs (follow) | `... logs -f artemis` |
| Stop, keep containers and data | `... stop` |
| Remove containers, **keep data** | `... down` |
| Remove containers and **delete all data** | `... down -v` |

More commands, flags explained, and the Redis and MariaDB specifics are in `notes/00-docker-commands.md`.

## Notes
- Image versions are pinned on purpose (a moving tag such as `latest` can change behaviour with no code change).
- Redis runs with persistence off by design: it only holds rebuildable presence data. MariaDB holds the truth.
- If a command says it cannot connect to the Docker API, start Docker Desktop first.
