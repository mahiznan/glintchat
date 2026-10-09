# Module 0: Environment Setup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A healthy local Docker Compose stack (Artemis 2.44.0, MariaDB, Redis) and the project skeleton, so every later module has a place to run.

**Architecture:** One `infra/docker-compose.yml` with three services on a shared network, each with a healthcheck and a named volume. Credentials live in `infra/.env` (local-only, not committed). Folder layout follows the spec.

**Tech Stack:** Docker 29 / Compose v5, `apache/activemq-artemis:2.44.0`, MariaDB (official image), Redis (official image).

## Global Constraints

- Artemis image pinned to `apache/activemq-artemis:2.44.0` (never `latest`).
- 1K concurrent users is the design target; 50K registered.
- Everything runs in Docker Compose on the local machine.
- Local-only credentials; no real secrets, nothing committed to `.env`.

---

### Task 1: Project skeleton and git

**Files:**
- Create: `.gitignore`, `notes/.gitkeep`, `labs/.gitkeep`, `services/.gitkeep`, `web/.gitkeep`, `tests/.gitkeep`, `infra/.gitkeep`

- [ ] **Step 1: Initialise the repo and folders**

```bash
cd /Users/rajeshkumarm/workspace/glint-chat
git init
mkdir -p notes labs services web tests infra
touch notes/.gitkeep labs/.gitkeep services/.gitkeep web/.gitkeep tests/.gitkeep infra/.gitkeep
```

- [ ] **Step 2: Add `.gitignore`**

```gitignore
infra/.env
node_modules/
target/
*.log
.DS_Store
```

- [ ] **Step 3: Verify**

Run: `git status --short`
Expected: lists `.gitignore`, `docs/`, and the folders; `infra/.env` does not appear (it does not exist yet).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: project skeleton and specs"
```

### Task 2: Compose stack with healthchecks

**Files:**
- Create: `infra/.env`, `infra/.env.example`, `infra/docker-compose.yml`

**Interfaces:**
- Produces (used by every later module): Artemis broker `localhost:61616` (all protocols), console `http://localhost:8161`; MariaDB `localhost:3306`, database `glint`, user `glint`; Redis `localhost:6379`. Network name `glint-net`. Volumes `artemis-data`, `mariadb-data`, `redis-data`.

- [ ] **Step 1: Write `infra/.env.example` and copy it to `infra/.env`**

```env
ARTEMIS_USER=artemis
ARTEMIS_PASSWORD=artemis
MARIADB_ROOT_PASSWORD=rootpw
MARIADB_DATABASE=glint
MARIADB_USER=glint
MARIADB_PASSWORD=glintpw
```

```bash
cp infra/.env.example infra/.env
```

- [ ] **Step 2: Write `infra/docker-compose.yml`**

```yaml
name: glint

services:
  artemis:
    image: apache/activemq-artemis:2.44.0
    environment:
      ARTEMIS_USER: ${ARTEMIS_USER}
      ARTEMIS_PASSWORD: ${ARTEMIS_PASSWORD}
      ANONYMOUS_LOGIN: "false"
    ports:
      - "61616:61616"   # core/AMQP/STOMP/MQTT/OpenWire acceptor
      - "8161:8161"     # web console
    volumes:
      - artemis-data:/var/lib/artemis-instance
    healthcheck:
      test: ["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/127.0.0.1/61616'"]
      interval: 5s
      timeout: 3s
      retries: 20
    networks: [glint-net]

  mariadb:
    image: mariadb:11
    environment:
      MARIADB_ROOT_PASSWORD: ${MARIADB_ROOT_PASSWORD}
      MARIADB_DATABASE: ${MARIADB_DATABASE}
      MARIADB_USER: ${MARIADB_USER}
      MARIADB_PASSWORD: ${MARIADB_PASSWORD}
    ports:
      - "3306:3306"
    volumes:
      - mariadb-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "healthcheck.sh", "--connect", "--innodb_initialized"]
      interval: 5s
      timeout: 3s
      retries: 20
    networks: [glint-net]

  redis:
    image: redis:7
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20
    networks: [glint-net]

networks:
  glint-net:

volumes:
  artemis-data:
  mariadb-data:
  redis-data:
```

- [ ] **Step 3: Validate the file**

Run: `docker compose --env-file infra/.env -f infra/docker-compose.yml config -q && echo OK`
Expected: `OK`

- [ ] **Step 4: Start the stack**

Run: `docker compose --env-file infra/.env -f infra/docker-compose.yml up -d --wait`
Expected: exits 0 after all three services report `healthy`. If Artemis stays unhealthy, run `docker compose --env-file infra/.env -f infra/docker-compose.yml logs artemis` and fix the cause before continuing (the healthcheck is a TCP probe on 61616; the log tells you whether the acceptor started).

- [ ] **Step 5: Write `README.md` with setup guidelines**

Create `README.md` at the repo root covering: prerequisites (Docker with Compose v2+, Java 21, Node), first-time setup (`cp infra/.env.example infra/.env`, then edit the placeholder values), start (`docker compose --env-file infra/.env -f infra/docker-compose.yml up -d --wait`), verify (`./tests/smoke.sh`), stop (`down`, and `down -v` to also wipe data), URLs and ports (Artemis console `http://localhost:8161`, broker `61616`, MariaDB `3306`, Redis `6379`), and the note that `infra/.env` is git-ignored and must never be committed.

- [ ] **Step 6: Commit**

```bash
git add infra/.env.example infra/docker-compose.yml README.md
git commit -m "feat(infra): compose stack with Artemis 2.44.0, MariaDB, Redis"
```

### Task 3: Verification script and smoke tests

**Files:**
- Create: `tests/smoke.sh`

- [ ] **Step 1: Write `tests/smoke.sh`**

```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
DC="docker compose --env-file infra/.env -f infra/docker-compose.yml"
set -a; source infra/.env; set +a

fail() { echo "FAIL: $1"; exit 1; }

# Artemis version is the pinned one
$DC exec -T artemis /var/lib/artemis-instance/bin/artemis version 2>&1 | grep -q "2.44.0" \
  || fail "Artemis is not 2.44.0"
echo "PASS artemis version 2.44.0"

# Artemis console answers
code=$(curl -s -o /dev/null -w '%{http_code}' -u "$ARTEMIS_USER:$ARTEMIS_PASSWORD" http://localhost:8161/console/)
[[ "$code" == "200" || "$code" == "302" ]] || fail "Artemis console returned $code"
echo "PASS artemis console ($code)"

# Artemis can send and receive a message
$DC exec -T artemis /var/lib/artemis-instance/bin/artemis producer \
  --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD" \
  --destination queue://smoke --message-count 1 --url tcp://localhost:61616 >/dev/null \
  || fail "Artemis producer"
$DC exec -T artemis /var/lib/artemis-instance/bin/artemis consumer \
  --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD" \
  --destination queue://smoke --message-count 1 --url tcp://localhost:61616 >/dev/null \
  || fail "Artemis consumer"
echo "PASS artemis send/receive"

# MariaDB accepts the app user and database exists
$DC exec -T mariadb mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE" -e "SELECT 1" >/dev/null \
  || fail "MariaDB app user"
echo "PASS mariadb"

# Redis
[[ "$($DC exec -T redis redis-cli ping)" == "PONG" ]] || fail "Redis ping"
echo "PASS redis"

echo "ALL PASS"
```

- [ ] **Step 2: Run it**

```bash
chmod +x tests/smoke.sh
./tests/smoke.sh
```
Expected: five `PASS` lines then `ALL PASS`. If the Artemis CLI path or flags differ in 2.44.0, check `docker compose ... exec artemis ls /var/lib/artemis-instance/bin` and `artemis producer --help`, and correct the script before committing.

- [ ] **Step 3: Prove persistence survives a restart**

```bash
DC="docker compose --env-file infra/.env -f infra/docker-compose.yml"
$DC exec -T mariadb mariadb -uroot -prootpw glint -e "CREATE TABLE t(i INT); INSERT INTO t VALUES (42);"
$DC restart mariadb && $DC up -d --wait
$DC exec -T mariadb mariadb -uroot -prootpw glint -e "SELECT i FROM t;"
$DC exec -T mariadb mariadb -uroot -prootpw glint -e "DROP TABLE t;"
```
Expected: the `SELECT` prints `42`.

- [ ] **Step 4: Commit**

```bash
git add tests/smoke.sh
git commit -m "test(infra): smoke test for broker, database and cache"
```

### Task 4: Learning note and gate

**Files:**
- Create: `notes/00-setup.md`

- [ ] **Step 1: Write `notes/00-setup.md` in your own words**

Fill each heading with your own answer (this is the learning step, so do not copy from the plan):

```markdown
# Module 0: Setup

## What each container does
- artemis:
- mariadb:
- redis:

## Ports and volumes (why each exists)

## Predictions before running
- If I `docker compose down` (no -v) and `up`, what survives?
- If I `docker compose down -v`, what survives?

## What I observed
```

- [ ] **Step 2: Run the two experiments and record what you observed**

```bash
DC="docker compose --env-file infra/.env -f infra/docker-compose.yml"
$DC down && $DC up -d --wait    # then check: is the MariaDB test data / Artemis queue still there?
$DC down -v && $DC up -d --wait # then check again
```

- [ ] **Step 3: Cross-questions (answer in the note; the assistant reviews before Module 1)**

1. Why is Artemis pinned to `2.44.0` and not `latest`? What can go wrong in production if you don't pin?
2. What is the difference between a container being "started" and "healthy", and why do later services need `depends_on: condition: service_healthy`?
3. The Artemis acceptor on 61616 serves several protocols on one port. Why would a bank still want separate acceptors per protocol or per client type?
4. Why does `down -v` lose data and plain `down` not? Which of our three services would be a disaster to lose in production, and which is rebuildable?
5. `infra/.env` is git-ignored. Why, and what would you use in a real bank deployment instead?
6. Our Compose file runs one Artemis. List three production risks of that and what Artemis offers to address each (hint: live/backup, replication).

- [ ] **Step 4: Commit**

```bash
git add notes/00-setup.md
git commit -m "docs: module 0 learning note"
```

**Gate:** all six questions answered, `tests/smoke.sh` prints `ALL PASS`, and the persistence experiments match your recorded observations. Then we write the Module 1 plan.
