# Artemis CLI reference and lessons (Module 1 labs)

Run from the repo root. Every command is shown as the wrapper and as the full command. The wrapper `./labs/art.sh` adds the login and broker address for you:

```
./labs/art.sh <args>
  =
docker compose --env-file infra/.env -f infra/docker-compose.yml exec artemis \
  /var/lib/artemis-instance/bin/artemis <args> \
  --user artemis --password artemis --url tcp://localhost:61616
```
(`artemis` / `artemis` are the defaults from `infra/.env`; use your own values if you changed them. `localhost:61616` is the broker as seen from inside the container.)

In the examples below, `FULL=` stands for that whole prefix and suffix.

## 1. Sending and receiving

| Goal | Wrapper |
|---|---|
| Send N messages to a queue | `./labs/art.sh producer --destination queue://lab.p2p --message-count 1 --message "m1"` |
| Publish to a topic (multicast address) | `./labs/art.sh producer --destination topic://lab.events --message-count 2 --message "event"` |
| Send straight to one queue of an address (FQQN) | `./labs/art.sh producer --destination queue://lab.events::pod-2 --message-count 1 --message "x"` |
| Receive N messages and print bodies | `./labs/art.sh consumer --destination queue://lab.p2p --message-count 3 --verbose` |
| Receive from one queue of an address (FQQN) | `./labs/art.sh consumer --destination queue://lab.events::pod-1 --message-count 2 --verbose` |

Full form, for example: `docker compose --env-file infra/.env -f infra/docker-compose.yml exec artemis /var/lib/artemis-instance/bin/artemis producer --destination queue://lab.p2p --message-count 1 --message "m1" --user artemis --password artemis --url tcp://localhost:61616`

Options: `--destination` (`queue://` = anycast, `topic://` = multicast, `address::queue` = a specific queue), `--message-count N` (send N, or stop after receiving N), `--message "text"` (body), `--verbose` (print received bodies), `--non-persistent`, `--msgttl ms`, `--group name`, `--properties '[{"type":"string","key":"k","value":"v"}]'`, `--filter "k='v'"` (consumer), `--durable` and `--clientID` (consumer), `--commit-interval N` (transaction batch), `--sleep ms`, `--receive-timeout ms`.

## 2. Observing

| Goal | Wrapper |
|---|---|
| Stats of one queue | `./labs/art.sh queue stat --queueName lab.p2p` |
| Stats of all queues | `./labs/art.sh queue stat` |
| Address and its bindings (which queues are linked) | `./labs/art.sh address show --name lab.events --bindings` |

Reading `queue stat`:

| Column | Meaning |
|---|---|
| CONSUMER COUNT | Consumers attached **right now**. 0 with messages waiting = nobody is processing |
| MESSAGE COUNT | In the queue right now (waiting + delivering) |
| MESSAGES ADDED | **Running total** that ever arrived. Only goes up. Not the same as count |
| DELIVERING COUNT | Handed to a consumer, not yet acknowledged |
| MESSAGES ACKED | Consumed and removed |
| ROUTING TYPE | ANYCAST (point-to-point) or MULTICAST (publish/subscribe) |

Rough relation: count = added - acked - expired - removed.

## 3. Creating and deleting

| Goal | Wrapper |
|---|---|
| Create a multicast address | `./labs/art.sh address create --name lab.events --multicast --no-anycast --silent` |
| Create a queue on an address | `./labs/art.sh queue create --name pod-1 --address lab.events --multicast --durable --preserve-on-no-consumers --silent` |
| Delete a queue | `./labs/art.sh queue delete --name pod-1 --removeConsumers` |
| Delete an address | `./labs/art.sh address delete --name lab.events` |

Notes: `--address` defaults to the queue's own name. `--silent` stops the CLI from prompting for unspecified options (the prompts' defaults are easy to get wrong, see lesson 5). `--preserve-on-no-consumers` keeps messages while no consumer is attached.

## 4. Deeper diagnostics through management (read-only)

The console serves a management API (Jolokia). Useful when the CLI table is not enough:
```bash
# per-queue attributes (replace names)
curl -s -u artemis:artemis -H "Origin: http://localhost:8161" \
 'http://localhost:8161/console/jolokia/read/org.apache.activemq.artemis:broker=%220.0.0.0%22,component=addresses,address=%22lab.events%22,subcomponent=queues,routing-type=%22multicast%22,queue=%22pod-1%22/PurgeOnNoConsumers,Filter,Paused,MessagesAdded,MessageCount'
# per-address counters: routed vs unrouted
curl -s -u artemis:artemis -H "Origin: http://localhost:8161" \
 'http://localhost:8161/console/jolokia/read/org.apache.activemq.artemis:broker=%220.0.0.0%22,component=addresses,address=%22lab.events%22/RoutedMessageCount,UnRoutedMessageCount,QueueNames,Paused'
```
Needs the `Origin` header. Broker name `0.0.0.0` is what our instance reports.

## 5. Lessons from the labs (each one found by running into it)

1. **Auto-create.** Sending to `queue://x` creates the address and a queue of the same name on first use. A typo silently creates a new queue. Strict production brokers often disable it (platform-team question).
2. **"Produced: N messages" does not mean delivered.** It only means the broker accepted them. A multicast address with no bound queue (or no queue that retains) drops them silently, with no error.
3. **`MESSAGES ADDED` is a running total** and never drops; `MESSAGE COUNT` is what is there now.
4. **Order matters for multicast.** A queue only receives messages published after it exists.
5. **`purge-on-no-consumers = true`** means a queue does not retain messages while it has no consumer. Queues created without `--silent` may get this from an interactive prompt default, or from the console. In our lab the pod queues showed `ADDED 0` although the address counted the messages as routed. Check with `PurgeOnNoConsumers` in section 4. Use `--preserve-on-no-consumers` when you want messages to wait. For per-pod chat queues, purge-on-no-consumers may be exactly what we want (no orphan growth); that is decision D11.
6. **A multicast message is stored once; each queue holds its own reference.** Each queue's reference is removed independently when that queue's consumer acknowledges it. One queue can be 0 while another still has messages. A stuck queue keeps the message body alive in broker memory and journal.
7. **`topic://` vs `queue://` when consuming.** A `topic://address::queue` consumer acts as a JMS non-durable subscriber and **deletes the queue when it closes**. Use `queue://address::queue` to read from a specific queue without destroying it.
8. **Names matter.** `pod1` is not `pod-1`. Check names with `queue stat` before assuming something is missing.
9. **A count of 0 never proves delivery.** Look at `MESSAGES ACKED`, the consumers' own output, and the address' `RoutedMessageCount`/`UnRoutedMessageCount`.

10. **Competing consumers share only messages still in the queue (prefetch).** A Core consumer has a client-side buffer (consumer window, default 1 MiB, in bytes). If messages are already waiting when the first consumer attaches, the broker pushes them all into its buffer (`DELIVERING COUNT` rises, `MESSAGE COUNT` falls). A second consumer that attaches later gets nothing, however slow the first one is. Measured in Lab B part 2 (`lab.compete2`, default window): A consumed everything, B nothing, `CONSUMER COUNT` 2. With `consumerWindowSize=0` in the connection URL (`--url "tcp://localhost:61616?consumerWindowSize=0"`, full command form) the messages are dealt one at a time: A took the first ones alone, then they alternated once B attached (`lab.compete3`). Window `0` is fair but costs a round trip per message; `-1` is unlimited buffering. If the holder dies, its whole buffer returns to the queue, redelivered. AMQP has link credit/prefetch instead of the window (Module 2).
11. **Durable subscriptions (Lab D).** `--durable --clientID c1` on a `topic://` makes a durable subscription whose queue is named `<clientID>.<subscription name>` (the CLI's subscription name looks like `Consumer ActiveMQTopic[lab\.durable], thread=0`; quote it in single quotes in zsh). It keeps messages while the subscriber is away (4 waiting messages arrived on reconnect); a non-durable subscriber's queue is deleted on close, so messages published while it was away are dropped at the address and never reach it. `MESSAGES ADDED` is the lifetime total (1 + 4 + 50 = 55 in the orphan check), `MESSAGE COUNT` is what is waiting. A durable queue nobody reads keeps growing until a TTL, a size limit or a delete; nothing cleans it automatically (orphan risk, D11). Deleting the queue removes the subscription: later publishes to the topic are dropped, they do not create a new queue.
12. **A `clientID` can be used by only one live connection.** Verified on 2.43.0: a second consumer with the same `--clientID c2` failed at connect with `Connection failed::clientID=c2 was already set into another connection`. Expected to be broker-wide (to verify with another topic). Consequence: a replacement pod reusing a clientID is rejected until the broker drops the old connection (same cost as `--max-consumers 1`, D11); on a shared broker, clientIDs need a naming convention.
13. **Selectors and message groups (Lab E).** A consumer `--filter "type='vip'"` receives only matching messages; non-matching ones stay in the queue, and a consumer without a filter takes whatever is left (it was never "normal only"). Property names and values are free; only the selector syntax is fixed (SQL-92 subset, case-sensitive strings, a misspelled property name matches nothing silently). A message no attached consumer matches is stuck until TTL or delete, while `CONSUMER COUNT` stays above 0: monitor queue depth and oldest message age, not only consumers. With `--group conv-A` the first message binds the group to one consumer and every later message of that group follows it (order preserved, no split); a new group goes to the next consumer, which is not guaranteed to be balanced. After the consumer was stopped cleanly (nothing in flight), the next conv-A message went to the other consumer (`lab.group2`). Not yet tested: failover with unacknowledged messages in flight (possible reordering, Task 9) and the broker memory cost of many group keys (Module 2).
14. **Persistence and TTL (Lab F).** Persistent messages (the CLI default) are written to the journal and survive a broker restart (`docker compose ... restart artemis` keeps the volume); `--non-persistent` messages do not. A persistent send that returns without error means the broker has journaled it, not that anyone received it and not that it survives losing the disk (sync settings to verify). `--msgttl` is in milliseconds; expired messages move to the expiry address (`ExpiryQueue` exists on this broker) where nobody looks unless monitored. The expiry scan interval was not observed (believed 30 s by default, to verify). In our chat persistence is useful for the notification-service durable queue, not for non-durable per-pod queues (deleted on restart anyway) and not for typing/presence signals.

## 6. Troubleshooting ladder ("I published but my queue shows 0")
1. Did the producer print `Produced: N`? (accepted by the broker only.)
2. `queue stat` for all queues: did the messages land in a different or auto-created queue?
3. `address show --name X --bindings`: is your queue linked to the address you published to?
4. Was the queue created **before** you published?
5. Per-queue management attributes: `PurgeOnNoConsumers`, `Filter`, `Paused`, `MessagesAdded`.
6. Per-address counters: `RoutedMessageCount` vs `UnRoutedMessageCount`.
7. Check your command against what you intended: `topic://` vs `queue://`, hyphens, spelling.

## 7. Which consumers share a queue (design rules, details in D11/D14)
- **Gateway pods:** one queue **per pod** on a multicast address; one consumer each. Multicast copies (no round robin).
- **notification-service replicas:** one shared, fixed-name durable queue on its own address `chat.notifications` (moved off `chat.events` on 2026-10-12 because of the 60-second TTL there); the replicas compete (each event processed once).
- Round robin happens only between consumers of **one queue**, and across the queues of an **anycast** address. Check equal `MESSAGES ADDED` across pod queues to prove every pod got every event.
