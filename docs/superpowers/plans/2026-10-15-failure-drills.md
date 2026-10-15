# Failure Drills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline, with a pause after every drill). Steps use checkbox (`- [ ]`) syntax. This plan is mostly **experiments the user runs**, not code. The few code and script steps are in Task 0.

**Goal:** Break the minimal happy-path slice on purpose, one failure at a time, and record for each failure what the system actually does, whether our design says it should, and which resources are (or are not) released automatically. **Then decide, drill by drill, whether to fix the gap now** (as the design decisions prescribe) **or defer it**, so the product matures as the drills go and the design decisions get applied and proven in the code. *(Changed on 2026-10-15 at the user's request; before this the drills only recorded gaps.)*

**Architecture:** The slice (chat-service on 8080, ws-gateway on 8081, lab Artemis, MariaDB) is run in "drill mode": two chat-service instances behind a small nginx, two gateways, and Toxiproxy between every pair of components so network faults (cut, slow, reset) can be injected without touching the code. A snapshot script prints the state of every component in one command. A small Node client plays the browser, including reconnecting.

**Tech Stack:** Spring Boot 4.1.1 services (unchanged except a diagnostics endpoint), Toxiproxy, nginx (image `nginx:1.25` is already on this machine), Node (v26 here, global `WebSocket`), the lab Docker stack, `lsof`, `kill` signals.

## Global Constraints

- **The user runs every drill command** and writes a prediction first (project rule). Claude prepares files, explains commands, reads the output the user pastes, and records results.
- **Find, decide, fix, retest.** A drill first records what the **unfixed** slice does (prediction, observation, verdict). Only then do we decide, for each GAP or DESIGN-BUG, between **FIX NOW**, **DEFER** and **WON'T FIX** (decided by the user; Claude gives a recommendation with the size of the change and the risk). A fix is applied **as the design decision prescribes** (cite the D-number or the spec/chat-design section), and the drill is **run again** to confirm it. The first run's result is never overwritten: the register keeps the "before" row and adds the retest.
- **Fix rules.** (1) A fix is made only after the user says "fix". (2) Small fix (a class, a setting, a test): done inline in this plan with the usual method: test first where it applies, explain every file and command, run the tests, say what changed. (3) Large fix (new component or protocol: Redis presence, authentication, the client sync protocol, notification-service, leader lock if it needs a new table plus tests across modules): it becomes its **own short plan** (writing-plans skill) and is executed next, or is DEFERRED to the backlog; it does not grow inside a drill. (4) A DESIGN-BUG changes the design documents first (spec, `docs/design-decisions.md`, sequence diagrams, `docs/chat-design.md`, `docs/architecture-diagram.md` and its image), then the code. (5) Every fix updates the documents it touches and the "Decided / To verify" status. (6) Later drills run on the fixed system: note in the register which fixes were already in when a drill ran, and re-check the Appendix A expectations of the remaining drills against the fixes made so far.
- **Code changes are now allowed in every task**, but each one is traceable to a drill id and a decision. Task 0 changes code only to make the system observable.
- **No commits** until the user says "commit". Never commit `infra/.env`. Suggested commit grain when asked: one commit per fix (code, tests and the documents it touches), referencing the drill id, so the history reads as drill → fix.
- Java 21 for every Maven command: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
- Do not start Docker Desktop for the user. Ask if it is not running.
- Verdicts use exactly these words: **PASS** (design holds in the slice), **GAP** (design is right, slice lacks it), **DESIGN-BUG** (the design itself is wrong or incomplete; update the design documents), **UNKNOWN** (could not tell; retest).
- Decisions use exactly these words: **FIX NOW**, **DEFER** (goes to the backlog with a reason), **WON'T FIX** (accepted, with the reason). After a fix, the retest result is **FIXED** (retest passed) or **STILL OPEN** (retest failed; fix again).
- Results go into `docs/failure-drills-results.md`, one row per drill with the columns Verdict, Decision, and Fix and retest.
- Read-only inspection commands may be run by Claude to check flags, announced first.

---

## How every drill is run (the loop)

1. Claude explains the failure, what the **design says should happen** (with the document reference), and each command.
2. **User writes a prediction** in their own words (one or two sentences) in `docs/failure-drills-results.md` or in the chat.
3. User runs the **Do** commands and the **Observe** commands, and pastes what they see.
4. Claude compares with the design expectation and says plainly: PASS, GAP, DESIGN-BUG or UNKNOWN, and why, and where it would hurt in production. Claude records the **before** row.
5. **Decide.** For every GAP or DESIGN-BUG Claude states: the design reference that prescribes the fix, the size (small / large), the risk, and a recommendation. The user chooses **FIX NOW / DEFER / WON'T FIX**. PASS needs no decision.
6. **Fix (if FIX NOW).** Per the fix rules: design documents first if it is a DESIGN-BUG; test first where it applies; code; run the tests (Claude says so); explain every file and command; update the documents the fix touches. Large fixes get their own short plan.
7. **Retest.** The user predicts again (one line: "now it should ..."), re-runs the same Do/Observe commands, and pastes the result. Claude records **FIXED** or **STILL OPEN**. Restart the services first (the drill environment runs the new code).
8. Claude records the row (verdict, decision, fix, retest). Pause. Next drill only on "next". No commit until the user says "commit".

Drills are ordered from simple to complex. Within a group, later drills reuse the state of earlier ones.

---

## File Structure

| File | Responsibility |
|---|---|
| `services/ws-gateway/.../socket/SocketRegistry.java` (modify) | add `socketCount()` and `userCount()` |
| `services/ws-gateway/.../DiagController.java` (create) | `GET /internal/diag` returns pod id, sockets, users, JVM threads (DEVELOPMENT only) |
| `services/ws-gateway/src/main/resources/static/index.html` (modify) | accept `?gw=<port>` so one page can talk to any gateway (socket and REST history both go through the gateway) |
| `infra/drill-snapshot.sh` (create) | read-only picture of gateways, outbox, DB connections and locks, broker queues |
| `infra/drill-proxies.sh` (create) | starts Toxiproxy and nginx and creates all proxies |
| `infra/drill-lb.conf` (create) | nginx config: one front for the two chat-service instances |
| `infra/drill-client.mjs` (create) | Node client: hold, send, raw, many, churn, flood |
| `docs/failure-drills-results.md` (create) | the results register |

---

### Task 0: Drill environment and observability

**Files:** see the File Structure table.

**Interfaces:**
- Produces: `GET http://localhost:<gatewayPort>/internal/diag` returning `{"podId":"pod-1","sockets":3,"users":2,"threads":47}`; `./infra/drill-snapshot.sh`; `./infra/drill-proxies.sh`; the port map below.

**Port map (drill mode)**

| Component | Listens on | Reached through |
|---|---|---|
| chat-service 1 | 8080 | nginx upstream |
| chat-service 2 | 8082 | nginx upstream |
| nginx (LB for chat-service) | 8091 | Toxiproxy `cs-lb` on **8090** (only the gateways use it; the page never calls chat-service) |
| gateway 1 (`POD_ID=pod-1`) | 8081 | Toxiproxy `gw1-ws` on **18081** (clients use 18081) |
| gateway 2 (`POD_ID=pod-2`) | 8083 | Toxiproxy `gw2-ws` on **18083** |
| Artemis | 61616 | Toxiproxy `artemis-gw1` **61617**, `artemis-gw2` **61619**, `artemis-cs` **61618** |
| MariaDB | 3306 | Toxiproxy `mariadb` **3307** |
| page | 8000 | `python3 -m http.server`, serves the static folder |
| Toxiproxy API | 8474 | `docker exec toxiproxy /toxiproxy-cli ...` |

One proxy per path means a fault can be injected between one component pair only (for example only gateway 1 to the broker).

- [ ] **Step 1: Registry counters, test first.** Create `services/ws-gateway/src/test/java/com/glint/gateway/socket/SocketRegistryTest.java`:

```java
package com.glint.gateway.socket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SocketRegistryTest {

    private static ClientSocket socket(String session, String user) {
        return new ClientSocket() {
            public String sessionId() { return session; }
            public String userId() { return user; }
            public void send(String json) {}
        };
    }

    @Test
    void countsSocketsAndUsers() {
        SocketRegistry registry = new SocketRegistry();
        ClientSocket a1 = socket("s1", "a");
        ClientSocket a2 = socket("s2", "a");
        ClientSocket b1 = socket("s3", "b");
        registry.register(a1); registry.register(a2); registry.register(b1);
        assertThat(registry.socketCount()).isEqualTo(3);
        assertThat(registry.userCount()).isEqualTo(2);

        registry.unregister(a1); registry.unregister(a2); registry.unregister(b1);
        assertThat(registry.socketCount()).isZero();
        assertThat(registry.userCount()).isZero(); // empty sets are removed, so the map cannot grow forever
    }
}
```
Run (expect compile failure): `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -f services/ws-gateway/pom.xml test -Dtest=SocketRegistryTest`

- [ ] **Step 2: Implement.** In `SocketRegistry.java` add:

```java
    public int socketCount() {
        return byUser.values().stream().mapToInt(Set::size).sum();
    }

    public int userCount() {
        return byUser.size();
    }
```
Create `services/ws-gateway/src/main/java/com/glint/gateway/DiagController.java`:

```java
package com.glint.gateway;

import com.glint.gateway.socket.SocketRegistry;
import java.lang.management.ManagementFactory;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** DEVELOPMENT ONLY: lets the failure drills see whether resources are released. Remove or protect in production. */
@RestController
public class DiagController {
    private final SocketRegistry registry;
    private final String podId;

    public DiagController(SocketRegistry registry, @Value("${gateway.pod-id}") String podId) {
        this.registry = registry; this.podId = podId;
    }

    @GetMapping("/internal/diag")
    public Map<String, Object> diag() {
        return Map.of("podId", podId, "sockets", registry.socketCount(), "users", registry.userCount(),
                "threads", ManagementFactory.getThreadMXBean().getThreadCount());
    }
}
```
Run the whole gateway suite: `mvn -f services/ws-gateway/pom.xml test`. Expected: all pass (13 tests).

- [ ] **Step 3: The page takes ports from the URL.** In `services/ws-gateway/src/main/resources/static/index.html` replace the line `const chatServiceUrl = 'http://localhost:8080';` with (final form after Step 3b below):

```js
  const gwPort = params.get('gw') || '8081';
  const gatewayHttp = 'http://localhost:' + gwPort;   // the page talks only to the gateway (socket and REST history)
```
and replace `new WebSocket('ws://localhost:8081/ws?userId=' + encodeURIComponent(user))` with `new WebSocket('ws://localhost:' + gwPort + '/ws?userId=' + encodeURIComponent(user))`. Check the script: `sed -n '/<script>/,/<\/script>/p' services/ws-gateway/src/main/resources/static/index.html | sed '1d;$d' > "$TMPDIR/page.js" && node --check "$TMPDIR/page.js"`.

- [x] **Step 3b (DONE 2026-10-15; the user decided FIX NOW after deciding that the MFE talks only to the gateway, D18; the gateway is one service for both socket and REST; the gateway-to-chat-service JWT is ignored in the slice).** Gateway: `ChatServiceClient.history(...)` (passes chat-service's status and body through) and `HistoryController` (`GET /conversations/{id}/messages`, header `X-User-Id` stub), `GatewayCorsConfig` (`gateway.cors.allowed-origin`), tests `ChatServiceClientTest`, `HistoryControllerTest`, `GatewayCorsTest`. chat-service: history moved to `/internal/conversations/{id}/messages`, `CorsConfig` and `chat.cors` deleted, `MessageApiTest` updated (20 tests pass). Page: history from the gateway, `cs` parameter removed. Effect on the drills: the page and the Node client talk only to the gateway ports (`18081`, `18083`); `8090` (nginx front) is used only by the gateways and by the `curl` drills against `/internal/...`. Drill A3, A9, A10 and F3 now exercise the production path (history through the gateway).

- [ ] **Step 4: `infra/drill-client.mjs`** (Node 22+ has a global `WebSocket`):

```js
// Development tool for the failure drills. It plays the browser.
//   node infra/drill-client.mjs hold <userId> <port[,port...]>             stay connected, print frames, reconnect through the port list
//   node infra/drill-client.mjs send <userId> <port> <conv> <clientMsgId> <body>   send one message, print the reply, exit
//   node infra/drill-client.mjs raw <userId> <port> <text>                 send one raw text frame, print the reply, exit
//   node infra/drill-client.mjs many <count> <port>                        open <count> sockets (users load-0...) and hold them
//   node infra/drill-client.mjs churn <count> <port>                       open and close <count> sockets quickly
//   node infra/drill-client.mjs flood <userId> <port> <conv> <count>       send <count> messages as fast as possible

const [mode, ...args] = process.argv.slice(2);
const url = (port, user) => `ws://localhost:${port}/ws?userId=${encodeURIComponent(user)}`;
const now = () => new Date().toISOString().slice(11, 23);

function hold(user, ports) {
  const list = ports.split(',');
  let attempt = 0;
  const seen = [];
  const open = () => {
    const port = list[attempt++ % list.length];
    const ws = new WebSocket(url(port, user));
    ws.onopen = () => console.log(now(), `connected to ${port}`);
    ws.onmessage = (e) => {
      const f = JSON.parse(e.data);
      if (f.type === 'message') {
        seen.push(f.message.seq);
        console.log(now(), 'message seq', f.message.seq, JSON.stringify(f.message.body).slice(0, 40));
      } else console.log(now(), e.data.slice(0, 120));
    };
    ws.onclose = (e) => {
      console.log(now(), `closed ${port} code=${e.code}; seqs seen so far: ${seen.join(',')}`);
      setTimeout(open, 1000);
    };
    ws.onerror = () => {};
  };
  open();
}

function once(user, port, frame) {
  const ws = new WebSocket(url(port, user));
  ws.onmessage = (e) => {
    const f = JSON.parse(e.data);
    if (f.type === 'ready') ws.send(frame);
    else if (f.type === 'ack' || f.type === 'error') { console.log(now(), e.data); ws.close(); }
  };
  ws.onclose = () => process.exit(0);
  ws.onerror = () => { console.log(now(), 'connection error'); process.exit(1); };
  setTimeout(() => { console.log(now(), 'no reply in 15 s'); process.exit(2); }, 15000);
}

function many(count, port) {
  let open = 0;
  for (let i = 0; i < count; i++) {
    const ws = new WebSocket(url(port, `load-${i}`));
    ws.onopen = () => { open++; };
    ws.onclose = () => { open--; };
    ws.onerror = () => {};
  }
  setInterval(() => console.log(now(), 'open sockets:', open), 5000);
}

async function churn(count, port) {
  let ok = 0, failed = 0;
  await Promise.all(Array.from({ length: count }, (_, i) => new Promise((resolve) => {
    const ws = new WebSocket(url(port, `churn-${i}`));
    ws.onopen = () => { ok++; setTimeout(() => ws.close(), Math.random() * 300); };
    ws.onclose = () => resolve();
    ws.onerror = () => { failed++; resolve(); };
  })));
  console.log(now(), `connected ${ok}, failed ${failed}`);
  process.exit(0);
}

function flood(user, port, conv, count) {
  const ws = new WebSocket(url(port, user));
  let acks = 0, errors = 0, start = 0;
  ws.onmessage = (e) => {
    const f = JSON.parse(e.data);
    if (f.type === 'ready') {
      start = Date.now();
      for (let i = 0; i < count; i++) {
        ws.send(JSON.stringify({ type: 'send', conversationId: conv, clientMsgId: crypto.randomUUID(), body: `flood ${i}` }));
      }
    } else if (f.type === 'ack') acks++;
    else if (f.type === 'error') errors++;
    if (acks + errors === count) {
      const s = (Date.now() - start) / 1000;
      console.log(now(), `acks ${acks} errors ${errors} in ${s}s = ${(acks / s).toFixed(1)}/s`);
      process.exit(0);
    }
  };
  ws.onerror = () => process.exit(1);
  setTimeout(() => { console.log(now(), `timeout: acks ${acks} errors ${errors}`); process.exit(2); }, 120000);
}

const modes = {
  hold: () => hold(args[0], args[1]),
  send: () => once(args[0], args[1], JSON.stringify({ type: 'send', conversationId: args[2], clientMsgId: args[3], body: args[4] })),
  raw: () => once(args[0], args[1], args[2]),
  many: () => many(Number(args[0]), args[1]),
  churn: () => churn(Number(args[0]), args[1]),
  flood: () => flood(args[0], args[1], args[2], Number(args[3])),
};
(modes[mode] ?? (() => console.log('unknown mode: ' + mode)))();
```
Check: `node --check infra/drill-client.mjs`.

- [ ] **Step 5: `infra/drill-lb.conf`** (one front for the two chat-service instances; a POST is not retried on the other instance, which mirrors a real client that must resend with the same `clientMsgId`):

```
events {}
http {
  upstream chat_service {
    server host.docker.internal:8080 max_fails=1 fail_timeout=5s;
    server host.docker.internal:8082 max_fails=1 fail_timeout=5s;
  }
  server {
    listen 8091;
    location / {
      proxy_pass http://chat_service;
      proxy_connect_timeout 1s;
      proxy_next_upstream error timeout;
    }
  }
}
```

- [ ] **Step 6: `infra/drill-proxies.sh`** (starts nginx and Toxiproxy and creates every proxy; run from the repo root). **Verify the Toxiproxy tag exists and the CLI flags with `--help` first** (Claude runs `docker exec toxiproxy /toxiproxy-cli --help` and `... create --help`, read-only, and fixes the script to match):

```bash
#!/usr/bin/env bash
# Failure-drill network: nginx in front of two chat-service instances, Toxiproxy between every component pair.
set -euo pipefail
cd "$(dirname "$0")/.."

docker rm -f drill-lb toxiproxy >/dev/null 2>&1 || true

docker run -d --name drill-lb -p 8091:8091 \
  -v "$PWD/infra/drill-lb.conf:/etc/nginx/nginx.conf:ro" nginx:1.25

docker run -d --name toxiproxy -p 8474:8474 \
  -p 61617:61617 -p 61618:61618 -p 61619:61619 -p 3307:3307 -p 8090:8090 -p 18081:18081 -p 18083:18083 \
  ghcr.io/shopify/toxiproxy:2.9.0
sleep 2

tp() { docker exec toxiproxy /toxiproxy-cli "$@"; }
tp create -l 0.0.0.0:61617 -u host.docker.internal:61616 artemis-gw1
tp create -l 0.0.0.0:61619 -u host.docker.internal:61616 artemis-gw2
tp create -l 0.0.0.0:61618 -u host.docker.internal:61616 artemis-cs
tp create -l 0.0.0.0:3307  -u host.docker.internal:3306  mariadb
tp create -l 0.0.0.0:8090  -u host.docker.internal:8091  cs-lb
tp create -l 0.0.0.0:18081 -u host.docker.internal:8081  gw1-ws
tp create -l 0.0.0.0:18083 -u host.docker.internal:8083  gw2-ws
tp list
```
Then `chmod +x infra/drill-proxies.sh`.

- [ ] **Step 7: `infra/drill-snapshot.sh`** (read-only; run it before and after every drill). **Verify the `queue stat` flags with `help queue stat` first** (some versions cap the rows; adjust):

```bash
#!/usr/bin/env bash
# One read-only picture of the whole system for the failure drills.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a
DB() { docker compose --env-file infra/.env -f infra/docker-compose.yml exec -T mariadb \
        mariadb -N -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE" -e "$1" 2>/dev/null; }

echo "== gateways: podId / sockets / users / threads"
for port in 8081 8083; do
  printf 'port %s: ' "$port"; curl -s --max-time 2 "localhost:$port/internal/diag" || printf 'down'; echo
done
echo "== messages (count, max seq) and outbox (unpublished, total)"
DB "SELECT COUNT(*), COALESCE(MAX(seq),0) FROM message;"
DB "SELECT COALESCE(SUM(published_at IS NULL),0), COUNT(*) FROM outbox;"
echo "== DB connections by command (pool size should come back after a drill)"
DB "SELECT command, COUNT(*) FROM information_schema.processlist WHERE user='$MARIADB_USER' GROUP BY command;"
echo "== DB transactions holding row locks (should be empty at rest)"
DB "SELECT trx_id, trx_state, trx_rows_locked, trx_mysql_thread_id FROM information_schema.innodb_trx;"
echo "== broker: queues on chat.events"
./labs/art.sh queue stat 2>/dev/null | grep -E "NAME|chat\.events" || echo "(none)"
```
Then `chmod +x infra/drill-snapshot.sh`.

- [ ] **Step 8: Results register** `docs/failure-drills-results.md` (created with this plan; see that file).

- [ ] **Step 9: Start drill mode (the user runs; Claude explains each line).** From the repo root, `set -a; source infra/.env; set +a; export JAVA_HOME=$(/usr/libexec/java_home -v 21)` in every terminal, then:

Terminal 1, chat-service 1:
```
mvn -f services/chat-service/pom.xml spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --spring.datasource.url=jdbc:mariadb://localhost:3307/$MARIADB_DATABASE --spring.artemis.broker-url=tcp://localhost:61618?blockOnNonDurableSend=true"
```
Terminal 2, chat-service 2: the same with `--server.port=8082`.
Terminal 3, gateway 1: `POD_ID=pod-1 mvn -f services/ws-gateway/pom.xml spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 --spring.artemis.broker-url=tcp://localhost:61617 --chat-service.base-url=http://localhost:8090 --gateway.cors.allowed-origin=http://localhost:8000"`
Terminal 4, gateway 2: `POD_ID=pod-2 ... --server.port=8083 --spring.artemis.broker-url=tcp://localhost:61619 ...` (same `--chat-service.base-url` and `--gateway.cors.allowed-origin`)
Terminal 5, the page: `python3 -m http.server 8000 --directory services/ws-gateway/src/main/resources/static`
Terminal 6 (control): `./infra/drill-proxies.sh` (before the Java services), then `./infra/drill-snapshot.sh`.

Expected: both gateways log `created queue chat.events::pod-1` / `pod-2`; the snapshot shows two gateways with 0 sockets and two queues. Page: `http://localhost:8000/?user=customer-1&gw=18081`.

- [ ] **Step 10: Pause.** Review the environment with the user. Do not commit until told.

---

### Task 1: Client and socket failures (group A)

Setup for the whole group: gateways and chat-services running, page served on 8000. `CONV=018f6b2a-0000-7000-8000-00000000c001`.

**A1. Customer closes the tab (clean close). What happens to the pod and the queue?**
- Design says: broker resources are per pod, never per user (D14); a closed socket is removed from the registry; nothing changes on the broker.
- Do: open the page as `customer-1` (`gw=18081`). `./infra/drill-snapshot.sh`. Close the tab. Snapshot again within a few seconds.
- Observe: gateway 1 `sockets` and `users` back to the earlier value; queue `chat.events::pod-1` unchanged (one consumer); no connection from the page left (`lsof -nP -iTCP:8081 -sTCP:ESTABLISHED`).

**A2. Customer's network disappears without a close (half-open connection).**
- Design says: the server detects a dead client within a bounded time (heartbeat; presence TTL about 30 s in the Phase 2 design, D14/D17) and releases the socket.
- Do: open the page as `customer-1` through `gw1-ws`. Cut it: `docker exec toxiproxy /toxiproxy-cli toxic add -t timeout -a timeout=0 -n cut_down -d gw1-ws` and the same with `-u -n cut_up` (verify direction flags with `--help`). Snapshot every minute for 5 minutes. Then, from RM, send a message to `customer-1`.
- Observe: does `sockets` ever drop on its own, and when? What happens when a message is written to the dead socket?

**A3. Page refresh during a burst of incoming messages.**
- Design says: the client opens the socket first, then fetches history, then merges by `seq` (D17, S5-S7); nothing is lost or shown twice.
- Do: open `customer-1`. In another terminal, `for i in $(seq 1 30); do curl -s -X POST localhost:8090/internal/conversations/$CONV/messages -H 'Content-Type: application/json' -d '{"userId":"rm-1","originSessionId":"x","clientMsgId":"'$(uuidgen)'","type":"text","body":"burst '$i'"}' >/dev/null; sleep 0.1; done` and refresh the `customer-1` tab several times while it runs. When it ends, reload once more.
- Observe: the tab shows all 30 in order, with no duplicate? Compare with the database count. Which messages are missing after a refresh, if any?

**A4. The same user on three tabs and two gateways.**
- Design says: each socket gets one copy; the sending socket gets only the ACK (D14, Task 6).
- Do: `customer-1` on `gw=18081` twice and on `gw=18083` once. Send from one.
- Observe: exactly one copy on each other tab on every gateway; none doubled on the sender.

**A5. Connection churn.**
- Design says: every socket's resources are released on close.
- Do: `./infra/drill-snapshot.sh`; `node infra/drill-client.mjs churn 500 18081`; snapshot again.
- Observe: `sockets` and `users` return to the starting values; `threads` returns to near the starting value; no error in the gateway log.

**A6. Slow client that stops reading.**
- Design says: a slow consumer must not hold up other users on the pod (the bounded send buffer in `ConcurrentWebSocketSessionDecorator`: 5 s, 64 KB).
- Do: `node infra/drill-client.mjs hold customer-1 18081` in terminal A; note its pid. Open `rm-1` in the browser (`gw=18081`). Freeze the client: `kill -STOP <pid>`. In another terminal, send 300 messages of about 4 KB as `rm-1` (`flood` with a long body, or the `curl` loop with a long string). In the `rm-1` tab, send a short message to the customer and watch how long the RM's own events take. Then `kill -CONT <pid>`.
- Observe: time for the RM tab to show its messages while the customer is frozen; `threads`; whether the frozen client's socket is closed by the server and when.

**A7. Bad input on the socket.**
- Design says: bad frames get an `error` frame, never crash the pod.
- Do: `node infra/drill-client.mjs raw customer-1 18081 'not json'`; `raw customer-1 18081 '{"type":"send"}'`; a 20 KB body: `raw customer-1 18081 "{\"type\":\"send\",\"conversationId\":\"$CONV\",\"clientMsgId\":\"$(uuidgen)\",\"body\":\"$(head -c 20000 /dev/zero | tr '\0' 'a')\"}"`; a 2 MB frame likewise.
- Observe: the reply frame or the close code for each; `sockets` afterwards; the gateway log.

**A8. A client floods.**
- Design says: per-user rate limits exist (D18 item 3, production).
- Do: `node infra/drill-client.mjs flood customer-1 18081 $CONV 2000`.
- Observe: acks, errors, messages per second; chat-service and MariaDB CPU (`top`); whether another user is slowed; the number of rows added.

**A9. Anyone can be anyone (identity stub).**
- Design says: the user id comes from a verified token (D18 item 1).
- Do: open `http://localhost:8000/?user=rm-1&gw=18081` in a private window without any login.
- Observe: you read the RM's conversation. Record it as the known stub, with where the real check goes.

**A10. A stranger and the same message sent twice.**
- Design says: non-participants are rejected on send and read (S15); a resend returns the original ids (S12).
- Do: page `?user=customer-2` (history status line); `node infra/drill-client.mjs send customer-2 18081 $CONV $(uuidgen) hi`; then with `customer-1`: `ID=$(uuidgen); node infra/drill-client.mjs send customer-1 18081 $CONV $ID same` twice.
- Observe: 403 on history; `error send_failed` for the stranger (is the reason visible to the client?); second send `duplicate: true` with the same `messageId`/`seq`; one database row.

- [ ] **Pause after each drill. Record each in the results register.**

---

### Task 2: Gateway pod failures and deployments (group B)

**B1. Gateway pod killed (crash).**
- Design says: the pod's queue is non-durable with auto-delete, so no orphan (D11, chat-design section 5); clients reconnect to another pod and resync from MariaDB (S9, D17).
- Do: `node infra/drill-client.mjs hold customer-1 18081,18083` and `hold rm-1 18083,18081`. Snapshot. `kill -9 $(lsof -ti tcp:8081 -sTCP:LISTEN)`. Watch `while true; do ./infra/drill-snapshot.sh | grep -E "pod-1|port"; sleep 1; done`. Send three messages from `rm-1` straight after the kill. Restart gateway 1.
- Observe: seconds until the `pod-1` queue disappears; where each client reconnects; which messages the customer client received and which are missing (compare `seq` list with the database); whether the new pod-1 creates its queue fresh.

**B2. Gateway pod terminated gracefully (SIGTERM, as in a rolling update).**
- Design says: closing sockets cleanly lets clients reconnect at once; the queue is removed; no event is lost for already-connected users (they resync).
- Do: same clients; `kill -TERM $(lsof -ti tcp:8081 -sTCP:LISTEN)`.
- Observe: close code the client sees; time from SIGTERM to process exit; queue removal time; messages missed.

**B3. Replacement pod with the same name while the old connection is a zombie.**
- Design says: `--max-consumers 1` rejects the second attach loudly; the replacement retries with backoff and must not report ready until attached (D11).
- Do: gateway 1 running with a client. Blackhole its broker path: `docker exec toxiproxy /toxiproxy-cli toxic add -t timeout -a timeout=0 -n cut artemis-gw1` (both directions). Start a third gateway process with `POD_ID=pod-1` on port 8085, pointing at the **unproxied** broker `tcp://localhost:61616`. Watch both logs and the queue. Remove the toxic afterwards.
- Observe: does the new process fail, retry, or accept sockets while deaf? How long until the broker drops the zombie? (the time to detect a dead client connection).

**B4. Rolling deployment of the gateways.**
- Design says: new pod up and attached before the old one is stopped; clients move; nothing lost thanks to resync (D17, S9, S16).
- Do: run `hold` clients for `customer-1` and `rm-1` on both ports. Start gateway 3 as `POD_ID=pod-3 --server.port=8084`; wait for its queue; then SIGTERM gateway 1; send messages continuously (`flood` at a low rate) during the swap.
- Observe: during the overlap both pods deliver; seqs received per client; the gap size at reconnect (what the sync protocol must cover); the time a socket can connect before the pod's queue is attached (there is no readiness check).

**B5. Gateway loses the broker while sockets stay open (network partition).**
- Design says: a pod that cannot receive events must not look healthy; clients detect a missed event within one heartbeat (S18/S19, D11 zombie cost 2).
- Do: clients connected to gateway 1. Cut `artemis-gw1` (as in B3). Send five messages as `rm-1` via gateway 2. Wait 90 seconds, snapshot, remove the cut, send two more. Reload the customer page.
- Observe: does the customer socket stay open while receiving nothing? When does the broker or the pod notice? After the cut is removed, is the pod's queue back, with the right attributes (`max-consumers 1`)? Which messages never reached the customer live?

**B6. Gateway cannot reach chat-service.**
- Design says: the sender gets a clear error quickly and can retry; threads are not held forever.
- Do: (a) `docker stop drill-lb` then send from a tab; (b) `docker start drill-lb`, then blackhole `cs-lb` (`toxic add -t timeout -a timeout=0 cs-lb`) and send 50 messages with `flood`; snapshot `threads`; wait; remove the toxic.
- Observe: (a) error frame time; (b) how long a message stays "sending..."; `threads` growth; whether the gateway ever gives up by itself.

**B7. Two gateways configured with the same pod id (misconfiguration).**
- Design says: the second must fail loudly (D11).
- Do: start a second process with `POD_ID=pod-1` against the real broker while gateway 1 is healthy. Connect a client to it.
- Observe: its log; does it accept sockets? does it deliver? does it say it is unhealthy?

**B8. Orphan queue: provisioned but never attached.**
- Design says: no orphan queues by construction (chat-design section 5).
- Do: `./labs/art.sh queue create --name pod-orphan --address chat.events --multicast --durable=false --max-consumers 1 --purge-on-no-consumers --auto-create-address=false` (verify the flag names with `help queue create` first). Wait 10 minutes; `./labs/art.sh queue stat --queueName pod-orphan`.
- Observe: whether auto-delete ever removes a queue that never had a consumer. If not, a gateway that crashes between provisioning and attaching leaves an orphan; with random pod names (a Deployment) these accumulate.

- [ ] **Pause after each drill. Record each in the results register.**

---

### Task 3: chat-service failures and deployments (group C)

**C1. Response lost after the commit.**
- Design says: the sender sees an error but the message exists; the resend with the same `clientMsgId` returns the original ids; one row, one event (S10, S12, D9).
- Do: `docker exec toxiproxy /toxiproxy-cli toxic add -t timeout -a timeout=500 -n eat_response -d cs-lb` (direction downstream; verify flags). `ID=$(uuidgen); node infra/drill-client.mjs send customer-1 18081 $CONV $ID once`. Remove the toxic. Send again with the same `$ID`.
- Observe: first reply is an error frame; the database already has the row; the second reply is `duplicate: true` with the same `seq`; the other participant saw the message once.

**C2. chat-service dies after the commit, before the relay publishes.**
- Design says: delayed, not lost (D4 outcome by crash point).
- Do: stop both chat-services' publishing: blackhole `artemis-cs`. Send 10 messages. Snapshot (`outbox unpublished` = 10). `kill -9` chat-service 1 and 2. Remove the cut. Start chat-service 1.
- Observe: the unpublished rows are published after the restart; clients (`hold`) receive them in `seq` order; time from start to delivery.

**C3. chat-service dies in the middle of a relay batch.**
- Design says: duplicates are possible and harmless; consumers dedupe (D9).
- Do: slow the broker path: `toxic add -t latency -a latency=500 artemis-cs`; send 60 messages quickly (`flood`); after about 15 seconds `kill -9` the chat-service; remove the latency; restart.
- Observe: `MESSAGES ADDED` on the pod queues vs the number of rows; the `hold` client logs the same `seq` twice; the page shows each message once.

**C4. One of two chat-service instances dies.**
- Design says: the other keeps serving; the failed requests are resent by clients with the same `clientMsgId`; no duplicate rows (D5, S10).
- Do: `node infra/drill-client.mjs flood customer-1 18081 $CONV 600` in a loop of slow sends (or the `curl` loop); `kill -9` chat-service 1 in the middle.
- Observe: number of error frames and their duration; nginx stops using the dead instance after `fail_timeout`; resend the failed ones with the same ids and check no duplicates; outbox unpublished goes to zero.

**C5. All chat-service instances dead.**
- Design says: live delivery of what is already committed continues until the outbox is empty; no new messages are accepted; recovery needs no restart of the gateway.
- Do: kill both. Open sockets stay up. Send from a tab. Check the gateways' diag. Start one chat-service.
- Observe: error time (nginx returns 502, how does the client see it?); sockets and threads unchanged; after restart sends work with no gateway restart.

**C6. SIGTERM while a request is in flight (graceful shutdown).**
- Design says: a rolling update does not lose or duplicate a message; an in-flight request either completes or fails cleanly and is resent safely.
- Do: only chat-service 1 running. In a MariaDB shell hold the conversation lock: `BEGIN; SELECT last_seq FROM conversation WHERE id = UNHEX(REPLACE('018f6b2a-0000-7000-8000-00000000c001','-','')) FOR UPDATE;`. Send from a tab (it waits). `kill -TERM` chat-service 1. Then `COMMIT;` in the shell.
- Observe: does the process wait for the request or exit at once? what does the client get? any row?

**C7. A crashed session holds the conversation lock.**
- Design says: row locks follow the connection and are released when it dies (D7).
- Do: in a MariaDB shell take the lock as in C6. Send from a tab; watch the "sending..." bubble. After 20 seconds close the shell forcibly (kill the client process or `KILL <thread id>` from the snapshot's lock list). Repeat once and do not release it.
- Observe: the send proceeds the instant the lock is released; without release, the time until `Lock wait timeout exceeded` (default 50 s) and how the user sees it.

**C8. Two relays and ordering.**
- Design says: one active relay (D8, ShedLock); `SKIP LOCKED` alone does not preserve order across relays (D7).
- Do: both chat-services running. `flood` 500 messages. `hold` client on `18081`.
- Observe: whether the client ever receives `seq` out of order (look for a lower number after a higher one); count them; compare `outbox.id` order with arrival order.

**C9. Log behaviour during a long failure.**
- Do: cut `artemis-cs` for 2 minutes with traffic.
- Observe: lines per minute in the chat-service log, stack traces, disk growth, CPU of the polling loop.

- [ ] **Pause after each drill. Record each in the results register.**

---

### Task 4: Broker failures (group D)

**D1. Broker stopped for two minutes, then started.**
- Design says: sends keep working (persist-before-publish); the outbox holds the events; they drain in order afterwards (D4, D6).
- Do: `docker compose --env-file infra/.env -f infra/docker-compose.yml stop artemis`; send 20 messages through the page; snapshot; wait two minutes; `... start artemis`.
- Observe: send latency while the broker is down (does `blockOnNonDurableSend` make the send fail or hang?); `outbox unpublished` grows; draining time; the gateways' recovery without a restart; their pod queues after the broker is back (name, attributes, `max-consumers`); whether the clients got the events live or only through reload.

**D2. Broker restart under traffic.**
- Design says: pod queues are non-durable and disappear with the broker; clients resync (D11, chat-design section 5).
- Do: `flood` at a low rate; `docker compose ... restart artemis`.
- Observe: same as D1, plus: does the gateway's listener re-attach by itself, and does it recreate the queue or does the broker auto-create it with different settings? (compare `queue stat` and the console before and after).

**D3. Network cut between chat-service and the broker.**
- Design says: relay send failures roll back; the row stays; sends continue (D4).
- Do: cut `artemis-cs` only; send; restore.
- Observe: time a relay round blocks (call timeout), row locks held (`innodb_trx` in the snapshot), whether sends to chat-service are slowed.

**D4. A stuck consumer (the pod stops reading) and queue growth.**
- Design says: address limits (`FAIL`, 60 s TTL) stop a stuck pod from hurting the broker (chat-design section 5; deferred in the slice).
- Do: `kill -STOP` gateway 2's JVM; `flood` 5000 messages; watch `queue stat` for `pod-2` and the broker memory in the console (http://localhost:8161); `kill -CONT`.
- Observe: queue depth, whether anything expires, the broker's memory or paging, the effect on gateway 1 and the relay.

**D5. How many broker connections does the relay use?**
- Design says: cheap publisher (a reused connection).
- Do: in the console read the broker's total connection count; send a burst of 100 messages; read it again.
- Observe: new connections per message (a `JmsTemplate` without a caching factory opens one per send).

- [ ] **Pause after each drill. Record each in the results register.**

---

### Task 5: Database failures (group E)

**E1. MariaDB stopped for one minute, then started.**
- Design says: no loss; sends fail clearly and quickly; recovery needs no restart.
- Do: `docker compose ... stop mariadb`; send from a tab; wait; `start mariadb`.
- Observe: error latency (the pool waits up to its connection timeout, default 30 s), relay log, recovery time, pool connections afterwards.

**E2. MariaDB unreachable but connections stay open (blackhole).**
- Do: `toxic add -t timeout -a timeout=0 mariadb`; send; wait two minutes; remove.
- Observe: how long a request hangs, whether the thread is ever released, and whether requests behind it queue up.

**E3. Locks and deadlocks under mixed load.**
- Design says: the relay's `SKIP LOCKED` does not block inserts; to verify under the default isolation level (D7).
- Do: both chat-services up; `flood` 1000 messages on two conversations at once (add a second seed conversation first); then `docker compose ... exec mariadb mariadb -u... -p... glint -e "SHOW ENGINE INNODB STATUS\G" | grep -A20 "LATEST DETECTED DEADLOCK"`.
- Observe: any deadlock or lock-wait error in the logs, the rate of failed sends.

**E4. Connection leak check after all of the above.**
- Do: snapshot at rest.
- Observe: DB connections equal the pool size of each chat-service and no transaction holds a lock.

- [ ] **Pause after each drill. Record each in the results register.**

---

### Task 6: Whole-system failures (group F)

**F1. Start-up order.**
- Do: (a) start a gateway with the broker stopped; (b) start chat-service with the broker stopped; (c) start chat-service with MariaDB stopped; (d) start a gateway with chat-service stopped.
- Observe: for each, does the process fail fast with a clear message, or start and hide the problem? exit code.

**F2. Everything dies and comes back.**
- Do: send messages so the outbox has unpublished rows; kill every Java process and `docker restart` Artemis; start chat-services, then gateways, then clients.
- Observe: outbox drains; old events reach clients as live events; the page and `hold` client show each message once; order; time to steady state.

**F3. Reconnect storm.**
- Do: `node infra/drill-client.mjs many 500 18081`, and a second set of `hold` clients that reconnect to `18081,18083`; `kill -9` gateway 1.
- Observe: how long gateway 2 takes to accept the 500 reconnects, its CPU and threads, chat-service load (history calls are not part of the slice; note it). The naive immediate retry in the client is what S16 forbids; record what the lack of backoff costs.

- [ ] **Pause after each drill. Record each in the results register.**

---

### Task 7: Resource-release audit

Fill this table from the drill results (one row each; evidence is the drill id).

| Resource | Created when | Crash or failure point | Released by | Evidence |
|---|---|---|---|---|
| WebSocket and registry entry | socket opens | clean close | `afterConnectionClosed` | A1 |
| | | network gone, no close | (to find) | A2 |
| | | pod crash | OS and clients | B1 |
| Pod queue | gateway start | pod crash, broker sees the close | auto-delete after the consumer leaves | B1 |
| | | crash before the consumer attaches | (to find) | B8 |
| | | zombie connection | broker connection check | B3, B5 |
| Gateway JMS listener thread | startup | broker lost | listener container recovery | B5, D2 |
| Gateway HTTP threads | per send | chat-service hung | (to find) | B6 |
| DB row locks | send transaction | session dies | connection close | C7 |
| DB connection pool | startup | DB outage | pool validation | E1, E4 |
| Outbox rows | commit | any crash | never lost; no cleanup yet (planned) | C2, C3 |
| Broker connections of the relay | each send | broker restart | (to find) | D5 |
| Relay row locks | relay round | broker hung | transaction timeout (to find) | D3 |
| nginx upstream marking | per instance | instance dies | `fail_timeout` | C4 |

- [ ] **Step 1:** fill the table with the user. **Step 2:** every "(to find)" that stays unfound is a GAP row in the register.

---

### Task 8: Close the drills and hand over what is left

- [ ] **Step 1:** finish `docs/failure-drills-results.md` (all rows have prediction, observation, verdict, decision, and the fix and retest where applied).
- [ ] **Step 2:** add a section to `docs/design-decisions.md` (D18 or a new D26) listing every GAP and DESIGN-BUG with the drill id and its outcome (FIXED, DEFER with reason, WON'T FIX with reason), and correct any design document whose statement a drill disproved. Re-run the whole suite and a final snapshot at rest to confirm the fixes did not break each other (regression pass over the drills that were FIXED).
- [ ] **Step 3:** update `CLAUDE.md` section 4 and the roadmap row for this plan.
- [ ] **Step 4:** show `git status` and a one-paragraph summary. Wait for "commit".

---

## Appendix A: Claude's expected verdicts (read only AFTER you wrote your own predictions)

These are predictions from reading the code and the design, not facts. Several may be wrong; finding out is the exercise.

| Drill | Expected | Reason |
|---|---|---|
| A1 | PASS | per-socket cleanup on close; queue is per pod |
| A2 | GAP | no heartbeat, no idle timeout; the entry stays until a write fails or the OS gives up |
| A3 | GAP | the page loads history before it opens the socket (S5 order is wrong), so a message sent between the two is missed |
| A4 | PASS | covered by the Task 8 test and Step 7 |
| A5 | PASS (unsure on threads) | |
| A6 | GAP (head-of-line blocking) | the JMS listener thread writes to sockets one after another, so a stalled socket can delay every user on the pod |
| A7 | PASS for bad frames; GAP for size | no maximum body is defined; the container's default frame limit decides |
| A8 | GAP | no rate limit |
| A9 | GAP (known) | the stub |
| A10 | PASS | |
| B1 | PASS for cleanup; clients lose events until reload | no client reconnect or resync in the page |
| B2 | UNKNOWN | depends on the container's shutdown behaviour |
| B3 | GAP | no readiness check; the process may look healthy while deaf |
| B4 | GAP | no readiness, no client reconnect protocol, no backoff |
| B5 | GAP | the headline silent-loss case (S18/S19); the queue is not recreated either |
| B6 | GAP | the HTTP client has no timeout |
| B7 | GAP | same as B3 |
| B8 | UNKNOWN (likely GAP) | auto-delete may only apply after a consumer has existed |
| C1 | PASS | idempotent resend |
| C2 | PASS | outbox |
| C3 | PASS (duplicates seen, deduped) | |
| C4 | PASS for data; some user-visible errors | |
| C5 | PASS | decoupled |
| C6 | UNKNOWN (likely GAP) | graceful shutdown is not configured |
| C7 | PASS for release; GAP for a lock-wait budget | |
| C8 | GAP | no leader lock; reordering likely under a burst |
| C9 | GAP | a retry every 200 ms with a stack trace, no backoff |
| D1 | PASS for data; GAP for recovery of the pod queue | |
| D2 | UNKNOWN | what the broker does with a consumer on a missing queue |
| D3 | GAP | the relay holds row locks while the send blocks |
| D4 | GAP | no address limits or TTL in the slice |
| D5 | GAP | a connection per message |
| E1 | GAP (slow failure) | the pool waits 30 s before it fails a send |
| E2 | GAP | no socket timeout |
| E3 | UNKNOWN | D7 asks us to verify gap locks |
| E4 | PASS | |
| F1 | PASS (fail fast) for a, c; check b, d | |
| F2 | PASS | |
| F3 | GAP | no backoff in the client (the naive client is the point) |

## Appendix B: Out of scope here

Capacity and soak tests (Module 11), the AI persona, the membership features (second RM), broker high availability (a platform topic), and production TLS. Authentication, Redis presence, the notification service and the full client sync protocol are **large fixes**: a drill that exposes them is recorded, and the user decides between a short plan of their own and DEFER. Everything DEFERRED goes to the backlog below.

## The backlog plan (written after the drills; only what was DEFERRED)

"Production implementation from the design documents." Since fixes are now applied drill by drill, this plan contains only the items marked DEFER in the register, plus the open items in D18 and the large items that got no plan of their own. The candidate backlog we already know about (each item is fixed during the drills if the user chooses FIX NOW, otherwise it stays here): heartbeat and idle timeout; client reconnect with the sync protocol and backoff (S1-S20); readiness and liveness probes; graceful shutdown; HTTP, JDBC and JMS timeouts; a pooled or cached publisher connection; relay leader lock and error backoff; queue re-creation after connection loss; address settings (`FAIL`, TTL, dead-letter, size); outbox cleanup job; authentication and service-to-service security; size and rate limits; observability (metrics, tracing, alerts); `chat.notifications` and the notification service; Redis presence; the membership and retention rules; the AI persona; the Artemis client version pin. It is written with the writing-plans skill only when the drills are finished.
