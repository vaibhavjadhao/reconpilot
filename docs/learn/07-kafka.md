# Kafka

**Why it earns a document.** "We used Kafka" appears on a lot of CVs. What
separates candidates is whether you can explain *what problem it solved that a
database table or an in-memory queue could not*, and whether you know the
handful of settings that decide between "reliable" and "silently loses
messages".

ReconPilot moved to Kafka for one concrete reason: work held in an in-memory
executor queue **vanished on a crash**, with nothing recorded anywhere.

---

## Table of contents

1. [What Kafka is, and is not](#1-what-kafka-is-and-is-not)
2. [Topics, partitions, offsets](#2-topics-partitions-offsets)
3. [Keys and ordering](#3-keys-and-ordering)
4. [Consumer groups and rebalancing](#4-consumer-groups-and-rebalancing)
5. [Producers: acks and idempotence](#5-producers-acks-and-idempotence)
6. [Consumers: the setting that loses messages](#6-consumers-the-setting-that-loses-messages)
7. [Delivery semantics](#7-delivery-semantics)
8. [Spring Kafka in practice](#8-spring-kafka-in-practice)
9. [Error handling and dead letters](#9-error-handling-and-dead-letters)
10. [Retention, compaction and replay](#10-retention-compaction-and-replay)
11. [Consumer lag](#11-consumer-lag)
12. [KRaft: life without ZooKeeper](#12-kraft-life-without-zookeeper)
13. [When NOT to use Kafka](#13-when-not-to-use-kafka)
14. [The traps, collected](#14-the-traps-collected)

---

## 1. What Kafka is, and is not

### The one-sentence answer

**Kafka is a durable, append-only log that many readers can read independently,
at their own pace.**

Not a queue in the RabbitMQ sense. The difference matters:

| | Traditional queue (RabbitMQ, SQS) | Kafka |
|---|---|---|
| On consume | the message is **removed** | the message **stays** |
| Reader position | the broker tracks it | the **consumer** tracks an offset |
| Replay | impossible | rewind the offset and read again |
| Multiple independent readers | needs fan-out configuration | natural — each group has its own offset |
| Ordering | weak | strict within a partition |

### The analogy

A traditional queue is a **pile of letters**: you take one off the top and it is
gone.

Kafka is a **newspaper archive**. Every issue is kept in order. Different
readers are at different dates. Nothing is consumed by being read — it ages out
on a schedule, not on being seen.

That is why Kafka suits event streaming: three services can each read the same
events for different purposes, and a new fourth service can start from the
beginning and catch up.

### The problem it solved here

Before Kafka, ReconPilot accepted an upload and put the work on an in-process
`ThreadPoolTaskExecutor` queue:

```
POST /api/ingest → 202 Accepted → queued in memory → worker parses it
                                        │
                                   kill -9 here
                                        ▼
                     work gone. No record. No error. No retry.
```

Kafka makes the work **durable**: the message is written to disk and replicated
before it is acknowledged. Verified on this project with an actual `kill -9`
mid-batch — the work was redelivered and completed after restart.

---

## 2. Topics, partitions, offsets

```
Topic: reconpilot.ingestion.requested

Partition 0:  [0][1][2][3][4][5]            ← append-only, ordered
Partition 1:  [0][1][2][3]
Partition 2:  [0][1][2][3][4][5][6][7]
                          ▲
                   consumer offset = 4
```

- **Topic** — a named stream of messages.
- **Partition** — a topic is split into partitions. Each is an ordered,
  immutable, append-only sequence stored on disk.
- **Offset** — a message's position within its partition. Monotonic, and
  **only meaningful within that partition**.

### Why partitions exist

**Parallelism.** One partition can be read by only one consumer in a group, so
the partition count is the ceiling on how much you can parallelise.

```
3 partitions, 2 consumers  →  one consumer gets 2 partitions
3 partitions, 3 consumers  →  one each ✅
3 partitions, 5 consumers  →  2 consumers sit IDLE ⚠️
```

> **You can increase partitions but never decrease them**, and increasing them
> changes which partition a key maps to — which breaks ordering for existing
> keys. Choose the number with room to grow.

### Replication

```
KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1     # ⚠️ single broker — no redundancy
```

Each partition has one **leader** and N-1 **followers**. Producers and consumers
talk to the leader; followers replicate. If the leader dies, an **in-sync
replica (ISR)** is promoted.

**Replication factor 1 means no replication**, so losing the broker loses the
data. ReconPilot runs one broker and records this honestly as a known defect
(D16) rather than pretending otherwise. Production wants **3 brokers at RF 3**,
tolerating one failure.

---

## 3. Keys and ordering

```java
template.send("reconpilot.ingestion.requested",
              event.batchId().toString(),     // ← the KEY
              event);
```

**The key decides the partition:** `partition = hash(key) % partitionCount`.

Consequences:

- Same key → same partition → **ordered relative to each other**.
- No key (`null`) → round-robin across partitions → **no ordering guarantee at
  all**.

> **The most important sentence about Kafka ordering:** *ordering is guaranteed
> within a partition, never across partitions.*

So the design question is always: **what must be ordered?**

- Events for one batch → key on batch id ✅ (what ReconPilot does)
- Events for one customer → key on customer id
- Nothing in particular → no key, maximum parallelism

> **Interview question:** *"How do you guarantee ordering in Kafka?"* — "You
> don't, globally. You guarantee it within a partition, and you choose a key so
> that everything which must be ordered lands in the same partition. For us that
> was the batch id, so a batch's ingestion and reconciliation events stay in
> order relative to each other while different batches process in parallel.
> Global ordering would mean a single partition, which throws away the
> parallelism you came for."

---

## 4. Consumer groups and rebalancing

```java
@KafkaListener(topics = "reconpilot.ingestion.requested",
               groupId = "reconpilot-ingestion")
void onMessage(IngestionRequested event) { ... }
```

A **consumer group** is a set of consumers sharing the work of a topic.

- Each partition is assigned to **exactly one** consumer in the group.
- **Different groups are independent** — each has its own offsets, so both can
  read every message.

```
Topic (3 partitions)
   ├──▶ group "ingestion"       (2 consumers — they split the partitions)
   └──▶ group "analytics"       (1 consumer — gets all 3, separate offsets)
```

Scale out by adding consumers to a group, up to the partition count.

### Rebalancing

When a consumer joins, leaves or dies, partitions are **reassigned**. During a
rebalance, consumption **stops**.

You will see this in the logs:

```
Successfully joined group with generation Generation{generationId=7, ...}
Adding newly assigned partitions: [reconpilot.ingestion.requested-1]
```

Causes of unwanted rebalances:

- **`max.poll.interval.ms` exceeded** — your listener took too long to process a
  batch, so the broker assumed the consumer was dead. **This is the common one.**
  Either process faster, reduce `max.poll.records`, or raise the interval.
- `session.timeout.ms` with missed heartbeats — usually a GC pause or a network
  blip.

**Cooperative sticky assignment** (the modern default) rebalances incrementally
instead of stopping everything — worth naming.

---

## 5. Producers: acks and idempotence

```properties
spring.kafka.producer.acks=all
spring.kafka.producer.properties.enable.idempotence=true
```

### acks

| Setting | Meaning | Risk |
|---|---|---|
| `acks=0` | fire and forget | loses freely |
| `acks=1` | the leader has it | loses if the leader dies before replicating |
| **`acks=all`** | **every in-sync replica has it** | slowest, safest ✅ |

With one broker, `acks=all` is identical to `acks=1` — but it is what makes a
multi-broker cluster durable, and defaulting it correctly now avoids a silent
data-loss window the day a second broker is added.

Pair it with:

```properties
min.insync.replicas=2      # broker/topic setting: acks=all needs at least 2 live
```

Without that, `acks=all` with only the leader in the ISR degrades to `acks=1`
without telling you.

### Idempotent producer

A producer that times out **retries**, and the original may already have been
written — producing a duplicate.

`enable.idempotence=true` gives each producer a session id and each message a
sequence number, so the broker discards a duplicate retry. It also implies
`acks=all`, retries, and `max.in.flight.requests.per.connection <= 5`.

**There is essentially no reason to turn it off.** It is the default in recent
Kafka versions.

---

## 6. Consumers: the setting that loses messages

**If you remember one thing from this document, make it this.**

```properties
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.listener.ack-mode=record
```

### What auto-commit does

With `enable.auto.commit=true` (the **Kafka default**), the consumer commits
offsets **on a timer** — every 5 seconds — regardless of whether the message was
actually processed.

```
t=0.0s   poll returns message 42
t=0.1s   your listener starts work
t=5.0s   ⏰ auto-commit fires: "we're done through offset 42"
t=5.1s   💥 the process crashes mid-work

on restart: the consumer resumes from offset 43.
Message 42 was never processed, will never be redelivered,
and nothing anywhere recorded that it was lost.
```

**Silent, permanent data loss.** No exception, no log line, no metric.

### What turning it off does

Spring commits the offset **only after your listener returns normally**. If it
throws, or the process dies, the offset is not advanced, and the message is
redelivered on restart.

**That is what makes redelivery-on-crash work at all**, and it is the whole
reason for moving to Kafka in the first place.

### auto-offset-reset

```properties
spring.kafka.consumer.auto-offset-reset=earliest
```

What a consumer group does when it has **no committed offset** — a brand new
group, or offsets aged out.

- `earliest` — start from the beginning. A new service catches up on history.
- `latest` (the default) — start from now. **A new group silently skips
  everything that already exists**, which is confusing the first time.

---

## 7. Delivery semantics

| Semantic | How | Reality |
|---|---|---|
| **At-most-once** | commit the offset *before* processing | may lose |
| **At-least-once** | process, *then* commit | **may duplicate** ← normal |
| **Exactly-once** | transactions + idempotent producer | possible, narrow, costly |

### At-least-once means your consumer must be idempotent

Because a crash between processing and committing causes redelivery,
**processing the same message twice must produce the same result as processing
it once.**

ReconPilot does this at the database level:

```sql
INSERT INTO recon_break (tenant_id, batch_id, external_txn_id, ...)
VALUES (?, ?, ?, ...)
ON CONFLICT (tenant_id, batch_id, external_txn_id) DO UPDATE
    SET delta_paise = EXCLUDED.delta_paise
RETURNING id;
```

Reconcile the same batch twice and you get the same 7,300 breaks, not 14,600.

> **Interview question:** *"How do you achieve exactly-once?"* — "Honestly, you
> usually don't — you achieve at-least-once delivery plus idempotent processing,
> which is *effectively* once from the outside. Kafka does offer genuine
> exactly-once via transactions and the idempotent producer, but it only covers
> Kafka-to-Kafka flows and costs throughput. The moment your consumer writes to
> an external database, the durable answer is a unique key and an upsert."

That answer is much stronger than claiming exactly-once, because the honest
version is what practitioners actually do.

---

## 8. Spring Kafka in practice

```java
@Service
class EventPublisher {
    private final KafkaTemplate<String, Object> template;

    void publishIngestion(IngestionRequested event) {
        template.send("reconpilot.ingestion.requested",
                      event.batchId().toString(), event);
    }
}

@Component
class IngestionWorker {

    @KafkaListener(topics = "reconpilot.ingestion.requested",
                   groupId = "reconpilot-ingestion",
                   concurrency = "3")           // 3 threads, up to partition count
    void onMessage(IngestionRequested event,
                   @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                   Acknowledgment ack) {
        process(event);
        // with ack-mode MANUAL you call ack.acknowledge() yourself
    }
}
```

### Serialization

ReconPilot configures JSON serialisers **in a `@Configuration` class, not in
properties** — deliberately:

> Spring Kafka refuses to let a `JsonDeserializer` be configured both ways
> ("must be configured with property setters, or via configuration properties;
> not both") — and it is right to. Two sources of truth for one setting is how
> configuration drifts.

```java
@Bean
ConsumerFactory<String, Object> consumerFactory() {
    var deserializer = new JsonDeserializer<>();
    deserializer.addTrustedPackages("in.reconpilot.*");   // ⚠️ never "*"
    ...
}
```

**`addTrustedPackages("*")` is a deserialisation vulnerability** — it lets a
message name any class on your classpath. Name your packages.

### Sending to Kafka inside a database transaction

```java
@Transactional
public void accept(Upload u) {
    repository.save(u);           // database
    publisher.publish(event);     // Kafka — NOT part of that transaction
}
```

**These are two systems and there is no shared transaction.** The database can
commit and the Kafka send fail, or vice versa. This is a *dual write*, and
ReconPilot records it as a known defect (D13) rather than pretending it is safe.

The standard solution is the **transactional outbox**: write the event to an
`outbox` table *in the same database transaction*, and have a separate process
(or change-data-capture, e.g. Debezium) publish from that table to Kafka. One
transaction, one source of truth, eventual publication.

---

## 9. Error handling and dead letters

```java
@Bean
DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
    var recoverer = new DeadLetterPublishingRecoverer(template);   // → topic.DLT
    return new DefaultErrorHandler(recoverer,
            new ExponentialBackOff(1_000L, 2.0));   // 1s, 2s, 4s...
}
```

### The poison-pill problem

A message that always fails will be retried forever, and because offsets are not
committed, **the partition stops advancing**. One bad message blocks everything
behind it.

Two choices, and you must make one deliberately:

1. **Dead-letter topic** — after N attempts, publish it to `topic.DLT` and
   commit, so the partition moves on. Someone inspects the DLT later.
2. **Stop the container** — halt and alert. Correct when silently skipping a
   message is worse than downtime, which in financial reconciliation it often
   is.

**Classify your exceptions:**

```java
errorHandler.addNotRetryableExceptions(
        DeserializationException.class,          // retrying will never help
        IllegalArgumentException.class);
```

Retrying a malformed message forever is pure waste. Retrying a timeout is
exactly right.

---

## 10. Retention, compaction and replay

```properties
retention.ms=604800000        # 7 days — then deleted regardless of consumption
retention.bytes=1073741824    # or by size
cleanup.policy=delete         # default
cleanup.policy=compact        # keep only the LATEST value per key
```

**Log compaction** turns a topic into a changelog: for every key, the most
recent value is kept forever. Perfect for "current state of every entity" —
replay the topic and you have rebuilt the state.

### Replay

Because nothing is destroyed by being read, you can reprocess:

```bash
kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
    --group reconpilot-ingestion --topic reconpilot.ingestion.requested \
    --reset-offsets --to-earliest --execute
```

This is genuinely powerful — fix a bug in a consumer, replay a week of events,
rebuild the projection. It only works if your consumer is idempotent, which is
another reason that property matters.

---

## 11. Consumer lag

**Lag = latest offset − committed offset.** How far behind the consumer is.

```bash
kafka-consumer-groups.sh --bootstrap-server kafka:9092 \
    --describe --group reconpilot-ingestion
```

```
TOPIC                          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
reconpilot.ingestion.requested 0          1523            1523            0
reconpilot.ingestion.requested 1          892             4210            3318  ⚠️
```

**Steadily growing lag means consumers cannot keep up.** Responses: add
consumers (up to the partition count), add partitions, or make processing
faster.

> **Nothing in ReconPilot alerts on lag — recorded as defect D14.** That is an
> honest gap worth naming in an interview, because the failure mode of a
> message system is *silence*: consumers stop, messages pile up, and every
> healthcheck stays green.

---

## 12. KRaft: life without ZooKeeper

Older Kafka needed **ZooKeeper** for cluster metadata — a second distributed
system to run, monitor and upgrade.

**KRaft** (Kafka Raft) moves metadata into Kafka itself, using the Raft
consensus protocol. ZooKeeper is gone as of Kafka 4.x.

ReconPilot runs a single node in KRaft mode — the same process acts as both
broker and controller:

```yaml
KAFKA_PROCESS_ROLES: broker,controller
KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
```

### The advertised-listener trap

**`advertised.listeners` is what Kafka tells clients to reconnect to.** A client
connects once, receives this address, and uses it thereafter.

- Spring running on your **host**, Kafka in Docker → must advertise `localhost`.
- Both in **Compose** → must advertise the service name `kafka`.

Get it wrong and the first connection succeeds and everything afterwards times
out — because the client is dutifully connecting to an address that means
something different from where it is standing.

---

## 13. When NOT to use Kafka

Being able to say this is as valuable as knowing how it works.

**Do not use Kafka for:**

- **Request/response.** Kafka is one-way. Use HTTP or gRPC.
- **A small job queue.** A database table with `SELECT ... FOR UPDATE SKIP
  LOCKED` is simpler, transactional with your data, and needs no extra
  infrastructure. Kafka is a lot of operational weight for a few hundred jobs a
  day.
- **Strict global ordering.** That means one partition, which means no
  parallelism.
- **Very large payloads.** The default message limit is 1 MB. Put the blob in
  object storage and send a reference — the **claim check** pattern. ReconPilot
  does exactly this: the settlement file is staged on disk and only the batch id
  travels through Kafka.
- **Anything needing a cross-system transaction.** Kafka and your database
  cannot commit together; see the outbox pattern.

> **A good interview answer:** "We used Kafka because work had to survive a
> process crash and be redelivered, and because reconciliation needed to consume
> the same ingestion events independently of ingestion itself. If it had only
> been a job queue for a few hundred tasks a day I would have used a Postgres
> table with `SKIP LOCKED` — same durability, one fewer system to operate."

---

## 14. The traps, collected

| # | Trap | The answer |
|---|---|---|
| 1 | `enable.auto.commit=true` | Commits on a timer; loses messages on crash |
| 2 | Expecting global ordering | Only within a partition; choose your key |
| 3 | `null` key | Round-robin; no ordering at all |
| 4 | More consumers than partitions | The extras sit idle |
| 5 | Decreasing partitions | Impossible; increasing breaks key→partition mapping |
| 6 | `acks=1` with a leader failure | Silent loss; use `acks=all` |
| 7 | `acks=all` without `min.insync.replicas` | Degrades to `acks=1` silently |
| 8 | `auto-offset-reset=latest` on a new group | Skips all existing messages |
| 9 | Slow listener | Exceeds `max.poll.interval.ms`; endless rebalancing |
| 10 | Non-idempotent consumer | At-least-once will duplicate your work |
| 11 | Claiming exactly-once | Only Kafka-to-Kafka; say at-least-once + idempotent |
| 12 | Poison pill without a DLT | One bad message blocks the whole partition |
| 13 | Retrying non-retryable errors | Classify exceptions |
| 14 | `addTrustedPackages("*")` | Deserialisation vulnerability |
| 15 | DB write + Kafka send in `@Transactional` | Dual write; use the outbox pattern |
| 16 | Advertising `localhost` in Compose | Clients reconnect to themselves |
| 17 | Replication factor 1 | Broker loss is data loss |
| 18 | No lag monitoring | Failure mode is silence; everything stays green |
| 19 | Large payloads through Kafka | 1 MB default; use the claim-check pattern |
| 20 | Kafka as a small job queue | A Postgres table with `SKIP LOCKED` is simpler |

---

## What to do next

1. **Reproduce the auto-commit bug.** Set `enable-auto-commit=true`, add a
   `Thread.sleep(10_000)` in the listener, and `kill -9` the app after six
   seconds. Restart and watch the message never arrive. You will never
   misconfigure this again.
2. **Watch a rebalance.** Start two instances with the same `groupId`, then kill
   one. The logs narrate the whole protocol.
3. **Check your lag** while a million-row ingest runs:
   `kafka-consumer-groups.sh --describe --group reconpilot-ingestion`.

Next: `08-react-redux-typescript.md`.
