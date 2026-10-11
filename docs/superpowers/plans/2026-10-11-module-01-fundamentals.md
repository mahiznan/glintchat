# Module 1: Messaging Fundamentals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a hands-on understanding of queues vs topics, competing consumers, durable subscriptions, filtering and ordering, persistence and TTL, back-pressure, and transactions and redelivery, using the running Artemis 2.44.0 and its CLI. Each lab ends with predictions checked against observations and cross-questions.

**Architecture:** No new services. A tiny wrapper script (`labs/art.sh`) runs the Artemis CLI inside the container with the credentials from `infra/.env`. Observation uses the Artemis console (http://localhost:8161) and `artemis queue stat`.

**Tech Stack:** Artemis CLI (`producer`, `consumer`, `queue`), the Artemis console, Docker Compose.

## Execution rules (from the user)
- Inline execution, **pause after every step** and explain what was done.
- **Never commit.** The user reviews, asks questions, and says "commit" when ready.
- One lab at a time. Predictions are written **before** running a lab. Cross-questions are answered before moving on.
- Each lab uses its own address names (`lab.*`) so labs do not interfere. Clean up with the console or `artemis queue delete` at the end of each lab.

## Global Constraints
- Artemis image `apache/activemq-artemis:2.44.0`; credentials from `infra/.env`.
- Chat context for every lesson: connect each concept back to the RM-customer chat design (spec, decisions D3, D4, D9, D14).
- Some results below are expectations from the Artemis documentation and experience, not verified on this stack. A different observation is valuable data: report it, do not hide it. Where a lab needs real acknowledgement control, it is deferred to the Module 5 Java lab.

## File structure
- Create: `labs/art.sh` (CLI wrapper)
- Create: `notes/01-fundamentals.md` (your notes, in your own words)
- Modify: `docs/superpowers/plans/2026-10-09-master-roadmap.md` (status)

---

### Task 1: CLI wrapper and baseline

**Files:**
- Create: `labs/art.sh`

- [ ] **Step 1: Write the wrapper**

```bash
#!/usr/bin/env bash
# Run an Artemis CLI command inside the container, with credentials from infra/.env.
# Usage: ./labs/art.sh producer --destination queue://lab.x --message-count 3
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a
exec docker compose --env-file infra/.env -f infra/docker-compose.yml exec artemis \
  /var/lib/artemis-instance/bin/artemis "$@" \
  --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD" --url tcp://localhost:61616
```

- [ ] **Step 2: Make it executable and run a round trip**

```bash
chmod +x labs/art.sh
./labs/art.sh producer --destination queue://lab.baseline --message-count 1 --message "hi"
./labs/art.sh consumer --destination queue://lab.baseline --message-count 1 --verbose
```
Expected: the consumer prints `Received hi`.

- [ ] **Step 3: Learn the observation tools**

```bash
./labs/art.sh queue stat --queueName lab.baseline
```
Expected: a table with `MESSAGE_COUNT`, `MESSAGES_ADDED`, `DELIVERING_COUNT`, `CONSUMER_COUNT`, `MESSAGES_ACKED`. Open the console, find `lab.baseline` under addresses, and match those columns to what you see. Remember the terms: **added** = ever arrived, **acked** = consumed and removed, **delivering** = handed to a consumer but not yet acknowledged, **message count** = waiting + delivering.

Pause for review.

### Task 2: Lab A, a queue holds messages (point-to-point)

**Concepts:** a queue stores messages until a consumer takes them; FIFO order; each message goes to exactly one consumer.

- [ ] **Step 1: Predict.** Send 5 messages to `lab.p2p` with no consumer running. What does `MESSAGE_COUNT` show? Then a consumer takes 3: what are the last two left, and in what order do they come out?

- [ ] **Step 2: Run**

```bash
for i in 1 2 3 4 5; do ./labs/art.sh producer --destination queue://lab.p2p --message-count 1 --message "m$i"; done
./labs/art.sh queue stat --queueName lab.p2p
./labs/art.sh consumer --destination queue://lab.p2p --message-count 3 --verbose
./labs/art.sh queue stat --queueName lab.p2p
./labs/art.sh consumer --destination queue://lab.p2p --message-count 2 --verbose
```

- [ ] **Step 3: Cross-questions**
1. A producer sent messages while no consumer existed, and nothing was lost. What is different about the earlier multicast case with no queue (the `lab.empty` experiment)?
2. After consuming, `MESSAGES_ADDED` stays at 5 but `MESSAGE_COUNT` goes to 0. What does each number tell an operator?
3. In our chat, which part (if any) is a plain queue like this, and which part is not?

Pause for review.

### Task 3: Lab B, competing consumers

**Concepts:** several consumers on one queue share the work; each message goes to one; per-message order is kept per consumer but not globally; prefetch can make distribution uneven.

- [ ] **Step 1: Predict.** Two consumers are waiting on `lab.work` when 20 messages arrive. Roughly how are they split? And if the 20 messages are sent **first**, and only then does consumer 1 start, who gets them?

- [ ] **Step 2: Run, consumers first** (two terminals, then a third for the producer)

Terminal 1 and 2:
```bash
./labs/art.sh consumer --destination queue://lab.work --message-count 1000 --receive-timeout 30000 --sleep 200
```
Terminal 3:
```bash
./labs/art.sh producer --destination queue://lab.work --message-count 20 --message "job"
```
Count how many each consumer printed (the consumer prints a line per message; count them, or compare `queue stat` while running).

- [ ] **Step 3: Run, messages first.** Stop consumers. Send 20 messages. Start consumer 1 alone (with `--sleep 200`), then start consumer 2 a few seconds later. Watch who processes what.

- [ ] **Step 4: Cross-questions**
1. What happened to the distribution in step 3, and why? (Hint: a consumer takes a batch ahead of time into a local buffer. Look up the `consumerWindowSize` setting.)
2. Messages 1 to 20 were sent in order. In what sense is the order preserved when two consumers share the queue, and in what sense is it lost?
3. For chat, if two gateway pods consumed one queue like this, what would go wrong? (This is why we use a queue **per pod** on a multicast address.)

Pause for review.

### Task 4: Lab C, publish/subscribe with a topic

**Concepts:** a topic (multicast) copies each message to every subscriber; subscribers only get messages sent while they exist.

- [ ] **Step 1: Predict.** Two subscribers are waiting on `topic://lab.news`. You publish 3 messages. How many does each get? Then you publish 2 more with **no** subscribers running. A third subscriber starts afterwards: does it get any?

- [ ] **Step 2: Run** (terminals 1 and 2 as subscribers)

```bash
./labs/art.sh consumer --destination topic://lab.news --message-count 100 --receive-timeout 60000 --verbose
```
Publisher:
```bash
./labs/art.sh producer --destination topic://lab.news --message-count 3 --message "news"
```
Then stop both subscribers, publish 2 more, start a new subscriber, and check what it receives. Look at `lab.news` in the console: what queues exist while subscribers are running, and what after they stop?

- [ ] **Step 3: Cross-questions**
1. What did the broker do with the 2 messages that had no subscribers? How would you detect it? (Recall `send-to-dla-on-no-route`.)
2. How is this the same as, and how different from, Phase 1 broadcast in our chat design?
3. Does a subscriber that is slow affect the other subscriber? What would an operator watch?

Pause for review.

### Task 5: Lab D, durable subscriptions

**Concepts:** a durable subscription keeps its queue while the subscriber is away; a non-durable one disappears.

- [ ] **Step 1: Predict.** A durable subscriber (clientID `c1`) reads one message and disconnects. While it is away, 4 messages are published. When it comes back, how many does it receive? What happens to the same experiment with a **non-durable** subscriber?

- [ ] **Step 2: Run**

```bash
./labs/art.sh consumer --destination topic://lab.durable --durable --clientID c1 --message-count 1 --receive-timeout 20000 --verbose
./labs/art.sh producer --destination topic://lab.durable --message-count 1 --message "first"
```
(Start the consumer first, publish while it waits, let it finish.) Then, with no subscriber attached, publish 4 more and look at the console:
```bash
./labs/art.sh producer --destination topic://lab.durable --message-count 4 --message "while-away"
```
Re-attach the durable subscriber with the same clientID and read 4. Repeat the idea with a non-durable subscriber (omit `--durable`) and compare.

- [ ] **Step 3: Orphan check.** Leave the durable subscription unused, publish 50 more, and watch `MESSAGE_COUNT` grow in the console. Then delete the subscription's queue in the console.

- [ ] **Step 4: Cross-questions**
1. What is the risk of a durable subscription whose subscriber never comes back? (This is the orphan per-pod queue problem, D11.)
2. In our design recovery comes from MariaDB, so do we need durable per-pod queues at all?
3. What does `clientID` identify, and what happens if two live consumers use the same one?

Pause for review.

### Task 6: Lab E, filtering and ordering with message groups

**Concepts:** selectors (filters) pick messages by properties; message groups keep related messages on one consumer to preserve their order.

- [ ] **Step 1: Predict.** Send 3 messages with property `type=vip` and 3 with `type=normal`. A consumer with filter `type='vip'` runs. How many does it get, and what is left in the queue?

- [ ] **Step 2: Run (filter)**

```bash
./labs/art.sh producer --destination queue://lab.filter --message-count 3 --message "v" --properties '[{"type":"string","key":"type","value":"vip"}]'
./labs/art.sh producer --destination queue://lab.filter --message-count 3 --message "n" --properties '[{"type":"string","key":"type","value":"normal"}]'
./labs/art.sh consumer --destination queue://lab.filter --filter "type='vip'" --message-count 3 --verbose
./labs/art.sh queue stat --queueName lab.filter
```

- [ ] **Step 3: Predict and run (groups).** Two consumers wait on `lab.group`. A producer sends 6 messages with `--group conv-A` and 6 with `--group conv-B`. Which consumer(s) get conv-A messages? Does either group get split?

```bash
# two consumer terminals
./labs/art.sh consumer --destination queue://lab.group --message-count 100 --receive-timeout 30000 --sleep 100 --verbose
# producer terminal
./labs/art.sh producer --destination queue://lab.group --message-count 6 --group conv-A --message "A"
./labs/art.sh producer --destination queue://lab.group --message-count 6 --group conv-B --message "B"
```

- [ ] **Step 4: Cross-questions**
1. Why might per-conversation ordering matter when workers share a queue, and how do message groups give it?
2. What do you give up when a group's consumer is slow or dies? (Think about hot groups and failover.)
3. Our design orders messages by `seq` from MariaDB and does not rely on broker ordering for correctness. Why is that the safer choice?

Pause for review.

### Task 7: Lab F, persistence and TTL

**Concepts:** persistent messages are written to the journal and survive a broker restart; non-persistent ones are not; TTL expires messages into the expiry address.

- [ ] **Step 1: Predict.** Send 3 persistent and 3 non-persistent messages to `lab.persist`, restart Artemis, and count. Then send 2 messages with a 5-second TTL, wait 10 seconds, and look at the queue.

- [ ] **Step 2: Run**

```bash
./labs/art.sh producer --destination queue://lab.persist --message-count 3 --message "p"
./labs/art.sh producer --destination queue://lab.persist --message-count 3 --message "np" --non-persistent
./labs/art.sh queue stat --queueName lab.persist
docker compose --env-file infra/.env -f infra/docker-compose.yml restart artemis
# wait until healthy (about 15 s), then
./labs/art.sh queue stat --queueName lab.persist
./labs/art.sh producer --destination queue://lab.ttl --message-count 2 --message "short" --msgttl 5000
sleep 10
./labs/art.sh queue stat --queueName lab.ttl
./labs/art.sh queue stat --queueName ExpiryQueue
```
(If `ExpiryQueue` does not exist on this broker, look in the console under addresses for the expiry address in effect.)

- [ ] **Step 3: Cross-questions**
1. A message is sent persistently and the producer got no error. Is it safe? What exactly has the broker promised? (Hint: when does a persistent send return?)
2. Where do expired messages go, and who looks at them? What does that mean for an unmonitored expiry queue?
3. Which of our chat events must be persistent, and which could tolerate loss because MariaDB can recover them?

Pause for review.

### Task 8: Lab G, back-pressure and slow consumers

**Concepts:** when producers outrun consumers, the queue grows; the broker may page to disk and eventually block or fail producers.

- [ ] **Step 1: Predict.** A producer sends 2,000 messages quickly; a consumer handles 5 per second. What happens to `MESSAGE_COUNT` over the next minute? What does the producer do?

- [ ] **Step 2: Run** (consumer in one terminal, producer in another, console open on `lab.slow`)

```bash
./labs/art.sh consumer --destination queue://lab.slow --message-count 2000 --receive-timeout 120000 --sleep 200
./labs/art.sh producer --destination queue://lab.slow --message-count 2000 --message "x"
```
Watch `MESSAGE_COUNT`, `MESSAGES_ADDED`, `MESSAGES_ACKED` for a minute. Then stop the consumer and look at what stays.

- [ ] **Step 3: Cross-questions**
1. Which numbers on a dashboard would warn you about a slow consumer before users complain?
2. On a **shared** production broker, how can one application's slow consumer hurt others? (Recall address-full policy BLOCK.)
3. In chat, which queue is most likely to build up, and what would stop it from growing forever?

Pause for review.

### Task 9: Lab H, transactions and redelivery

**Concepts:** a consumer that dies before it commits or acknowledges causes redelivery; this is the basis of at-least-once delivery.

- [ ] **Step 1: Predict.** 10 messages are queued. A consumer reads in transactions of 5 (`--commit-interval 5`) with a 1-second delay per message, and you press Ctrl-C after it has printed 3 messages. After the interruption, how many messages are in the queue, and how many deliveries have been attempted?

- [ ] **Step 2: Run**

```bash
./labs/art.sh producer --destination queue://lab.redeliver --message-count 10 --message "r"
./labs/art.sh consumer --destination queue://lab.redeliver --commit-interval 5 --message-count 10 --sleep 1000 --verbose
# press Ctrl-C after 3 messages
./labs/art.sh queue stat --queueName lab.redeliver
```
Look in the console at the messages' delivery counts (queue, "Browse"). Then run the consumer to completion and compare.

- [ ] **Step 3: Cross-questions**
1. Were the 3 uncommitted messages lost, consumed or redelivered? What does that tell you about "at-least-once"?
2. A consumer that processes a message and then dies **before** acknowledging: what does the user of your system see, and which design rule makes that harmless? (Recall dedupe by `messageId`, D9.)
3. What would "at-most-once" and "exactly-once" mean here, and which one does a broker with persistent messages and acknowledgements actually give you?

Acknowledgement modes in code (auto, client, transacted) are demonstrated with a small Java client in Module 5.

Pause for review.

### Task 10: Notes, quiz and gate

**Files:**
- Create: `notes/01-fundamentals.md`
- Modify: `docs/superpowers/plans/2026-10-09-master-roadmap.md` (mark Module 1 done)

- [ ] **Step 1: Write your notes in your own words**

```markdown
# Module 1: Messaging fundamentals

## Queue vs topic (anycast vs multicast), in my words

## Labs: prediction, observation, surprise
- A (queue):
- B (competing consumers):
- C (topic):
- D (durable):
- E (filter and groups):
- F (persistence and TTL):
- G (back-pressure):
- H (redelivery):

## Delivery guarantees: at-most, at-least, exactly once, and what our chat uses

## What I would monitor on a broker, and why
```

- [ ] **Step 2: Gate quiz.** The assistant asks these orally and you answer without notes:
1. Explain queue vs topic with a bank-chat example for each.
2. Why do two consumers on one queue not both see every message, and what is the chat consequence?
3. What does a durable subscription cost, and when is it worth it?
4. Walk through what happens when a consumer dies halfway through a batch.
5. What are the three delivery guarantees, and which does the chat design achieve at each hop (D10)?
6. Name three numbers you would alert on for a slow consumer.

**Gate:** all labs run with predictions recorded, all cross-questions answered, quiz passed. Then we write the Module 2 plan (Artemis deep dive).
