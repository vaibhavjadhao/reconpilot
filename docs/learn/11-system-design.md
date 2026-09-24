# System design for a two-year engineer

**What is actually expected of you.** Nobody asks a two-year candidate to
design Twitter from scratch and get it right. What they are testing is whether
you can **reason out loud**: clarify the problem, estimate, make a decision,
name its trade-off, and notice what you have broken.

The most common failure is not ignorance. It is **jumping straight to
"microservices, Kafka, Redis, Cassandra"** without ever asking how much data
there is.

Every example here is from ReconPilot, which is deliberately small — and being
able to explain why a small design was correct is worth more than reciting a
large one.

---

## Table of contents

1. [The framework](#1-the-framework)
2. [Requirements: functional and non-functional](#2-requirements-functional-and-non-functional)
3. [Back-of-envelope estimation](#3-back-of-envelope-estimation)
4. [The building blocks](#4-the-building-blocks)
5. [Scaling: vertical, horizontal, and what breaks](#5-scaling-vertical-horizontal-and-what-breaks)
6. [Caching](#6-caching)
7. [Databases: SQL, NoSQL, sharding, replication](#7-databases-sql-nosql-sharding-replication)
8. [CAP and consistency](#8-cap-and-consistency)
9. [Asynchronous work and queues](#9-asynchronous-work-and-queues)
10. [Idempotency](#10-idempotency)
11. [Backpressure and failure](#11-backpressure-and-failure)
12. [Multi-tenancy](#12-multi-tenancy)
13. [Observability](#13-observability)
14. [Walking through ReconPilot](#14-walking-through-reconpilot)
15. [What to say when you don't know](#15-what-to-say-when-you-dont-know)

---

## 1. The framework

Six steps. Say them out loud; interviewers are scoring your process, not your
diagram.

```
1. Clarify        — ask questions. Never start designing immediately.
2. Estimate       — numbers first. They decide the whole design.
3. API            — what operations exist?
4. Data model     — what is stored, and how is it queried?
5. High-level     — draw the boxes and arrows.
6. Deep dive      — the interviewer picks one; go deep. Name trade-offs.
```

**Spend real time on steps 1 and 2.** A candidate who asks "how many users?"
and "how fresh must this be?" before drawing anything is already in the top
half.

---

## 2. Requirements: functional and non-functional

### Functional — what it does

```
- A merchant uploads a settlement file
- The system recomputes the expected fee for every row
- It compares against what was charged and records differences
- An analyst reviews differences and raises claims
```

### Non-functional — how well it must do it

| | ReconPilot's answer |
|---|---|
| **Consistency vs availability** | Consistency. A wrong number is worse than a slow one. |
| **Latency** | Upload must respond in under a second; processing may take minutes. |
| **Throughput** | ~0.4 writes/second average. Bursty — a million rows at month end. |
| **Durability** | Absolute. A lost settlement row is a lost claim. |
| **Availability** | 99% is fine. Reconciliation is not a payment path. |

### The move that impresses

**Deliberately rank two of them LOW and say why.**

> "Availability is a low priority here. This is a back-office reconciliation
> tool, not a payment authorisation path — if it is down for an hour, the files
> are still there and we process them afterwards. I would trade availability
> for consistency every time, because the product's entire value is being
> independently *right* about money."

That is a senior answer. Candidates who say everything is critical have not
made a decision.

---

## 3. Back-of-envelope estimation

### Numbers worth memorising

| | |
|---|---|
| Seconds in a day | ~86,400 (≈10⁵) |
| Seconds in a month | ~2.6 million |
| 1 million writes/day | ~12 writes/second |
| L1 cache reference | 1 ns |
| Main memory | 100 ns |
| SSD random read | 100 µs |
| Network, same datacentre | 0.5 ms |
| Disk seek (spinning) | 10 ms |
| Network, India ↔ US | ~150 ms |

**The 100× rule:** memory is ~100× faster than SSD, SSD ~100× faster than a
disk seek. That is why caching works.

### Doing it on ReconPilot

```
1,000 merchants × 1,000 transactions/month = 1,000,000 rows/month

Writes:   1,000,000 / 2.6M seconds  ≈  0.4 writes/second
Storage:  456 bytes/row (measured)  ×  1M  ≈  456 MB/month  ≈  5.5 GB/year
          indexes add ~40%           →  ~7.7 GB/year
Reads:    an analyst refreshing a dashboard — single digits per second
```

### The conclusion that matters

> **"0.4 writes per second is nothing. One PostgreSQL instance handles this for
> years without effort. This is a *correctness* problem, not a *scale* problem
> — so I am going to spend my complexity budget on being right, not on being
> big."**

Saying that is a strong signal. It shows you estimated *before* designing, and
that you know complexity has a cost.

**The burst matters more than the average.** A million rows arriving in one
file is the real design constraint — hence streaming ingestion and a queue,
not sharding.

---

## 4. The building blocks

| Component | Job | Use when |
|---|---|---|
| **Load balancer** | spread traffic, health-check | more than one app instance |
| **App server** | business logic, stateless | always |
| **Database** | durable state, transactions | always |
| **Cache** | fast repeated reads | reads dominate and staleness is tolerable |
| **Queue / log** | decouple, absorb bursts, survive crashes | slow or bursty work |
| **Object storage** | large blobs | files, images, backups |
| **CDN** | static assets near users | global users |
| **Search index** | full-text, faceted search | SQL `LIKE` is not enough |

### Stateless application servers

**Keep no per-user state in the app.** Then any instance can serve any request,
and scaling is just adding instances.

ReconPilot uses a **JWT** rather than a server session for exactly this reason:
the token is validated with a secret, with no session store and no sticky
sessions. The trade-off — a JWT cannot be revoked before it expires — is a real
cost accepted deliberately, and naming it is the point.

---

## 5. Scaling: vertical, horizontal, and what breaks

**Vertical** — a bigger machine. Simple, instantly effective, has a ceiling,
and is a single point of failure.

**Horizontal** — more machines. Effectively unlimited, and drags in every hard
distributed-systems problem.

> **Scale vertically first.** It is astonishing how far one large machine goes,
> and the operational simplicity is worth real money. Most systems that "need"
> horizontal scaling need an index.

### What horizontal scaling breaks

- **In-memory state** — a session or cache on instance A is invisible to B.
- **Scheduled jobs** — now they run on every instance. You need a leader, or a
  lock (`pg_advisory_lock` is enough).
- **Rate limiting** — a per-instance counter lets N× through.
- **The database** — ten app servers all talk to one database. **The database
  is almost always the real bottleneck.**

### The order to reach for things

```
1. Add an index                     ← free, and usually the answer
2. Fix N+1 queries
3. Cache the hot reads
4. Vertical scale
5. Read replicas
6. Horizontal scale the app tier
7. Shard the database               ← last resort, changes everything
```

---

## 6. Caching

### Where a cache can live

```
Browser → CDN → Load balancer → App (local cache) → Redis → Database
```

Each layer is faster and staler than the one behind it.

### Patterns

- **Cache-aside** — check cache, miss → read database → populate. The default.
- **Write-through** — write both together. Consistent, slower writes.
- **Write-behind** — write cache now, database later. Fast, can lose data.

### Eviction

`LRU` (least recently used) is the sensible default. `LFU` suits skewed
popularity. TTL bounds staleness regardless.

### The hard parts

- **Invalidation.** The classic joke is about this. Prefer a **short TTL** over
  clever invalidation logic — being right for 60 seconds is usually enough and
  is far simpler than getting every invalidation path correct.
- **Stampede.** A popular key expires and a thousand requests miss at once.
  Mitigate with jittered TTLs or a lock so one caller refreshes.
- **What not to cache.** Anything where stale means *wrong* rather than merely
  *old*: balances, permission checks, anything a regulator reads.

### ReconPilot's one cache, and why

AI format discovery maps an unfamiliar file's columns to canonical fields. It
is **cached on a SHA-256 fingerprint of the header row**, so a million-row file
costs one model call the first time and none afterwards.

That is the right shape for a cache: expensive to compute, stable for a given
key, and a stale hit is *still correct* because the same header means the same
mapping.

---

## 7. Databases: SQL, NoSQL, sharding, replication

### Choosing

**SQL when** you need transactions, joins, and constraints — anything involving
money, almost always.

**NoSQL when** the access pattern is a known key lookup at enormous scale, the
schema is genuinely variable, or you need write throughput one node cannot give.

> **The honest answer for most interviews:** "PostgreSQL, until I have a
> measured reason not to. It does JSON, full-text search, and read replicas, and
> I get transactions and constraints for free. Reaching for a document store
> before I have a problem is choosing a harder operational life for no benefit."

### Replication

```
        writes                reads
          │                  ╱    ╲
       PRIMARY ──async──▶ REPLICA  REPLICA
```

- Scales **reads**, not writes.
- Gives you failover.
- **Replication lag** means a read straight after a write may not see it —
  "read your own writes" needs the primary, or a sticky read.

### Sharding

Split the data across databases by a key.

```
shard = hash(tenant_id) % shardCount
```

| Strategy | Trade-off |
|---|---|
| Hash | even spread; range queries hit every shard |
| Range | range queries are cheap; hotspots (everyone in 2026) |
| Directory | flexible; the lookup is a new single point of failure |

**What you lose:** cross-shard joins, cross-shard transactions, and the ability
to change the shard key without a migration. **Resharding is genuinely
painful.**

> **Say this:** "I would shard last. Before that: indexes, caching, read
> replicas, archiving cold data, and a bigger machine. Sharding costs you joins
> and transactions, which for a financial system is most of why I chose a
> relational database."

---

## 8. CAP and consistency

### CAP, stated correctly

**When the network partitions, you must choose Consistency or Availability.**

It is not "pick two of three". A partition is not optional — it *will* happen —
so the real question is what you do during one.

- **CP** — refuse to answer rather than answer wrongly. (PostgreSQL with
  synchronous replication, ZooKeeper, etcd.)
- **AP** — answer with possibly-stale data. (Cassandra, DynamoDB by default.)

**When there is no partition, you get both.** Most of the time this is not a
live trade-off, which is why PACELC is the more useful refinement: *else*
(normal operation) you trade **L**atency against **C**onsistency.

### Consistency models

| Model | Means |
|---|---|
| **Strong** | every read sees the latest write |
| **Eventual** | reads converge, given time |
| **Read-your-writes** | *you* see your own writes; others may lag |
| **Monotonic reads** | you never see time go backwards |

### ReconPilot's position

> "This is CP. The entire product is a claim to be independently right about
> money. If the database is unavailable I would rather return an error than a
> number I cannot defend — a wrong reconciliation is worse than no
> reconciliation, because someone might file a claim on it."

---

## 9. Asynchronous work and queues

### The rule

**Anything slow, or anything whose duration scales with input size, belongs out
of the request.**

```
❌  POST /ingest  →  parse 2 GB  →  200 OK          (60+ seconds)
✅  POST /ingest  →  stage + queue  →  202 Accepted  (0.5 seconds)
                            │
                            ▼
                    worker parses it
                            │
                            ▼
              GET /ingest/{id}  →  status
```

**202 Accepted** means "valid, scheduled, not done". Holding an HTTP connection
open for a minute hits every proxy timeout between the browser and the server,
and a retry re-uploads two gigabytes.

### Queue vs log

| | Queue (SQS, RabbitMQ) | Log (Kafka) |
|---|---|---|
| On read | removed | retained |
| Replay | no | yes |
| Independent consumers | fan-out config | natural |
| Ordering | weak | within a partition |

ReconPilot uses Kafka because ingestion and reconciliation consume the same
events independently, and because replay is valuable when a consumer had a bug.

### The claim-check pattern

Do not put a 2 GB file in a message. **Stage the blob, send the reference.**
ReconPilot writes the upload to disk and sends only the batch id.

### The dual-write problem

```java
@Transactional
void accept(Upload u) {
    repository.save(u);        // database
    publisher.publish(event);  // Kafka — NOT in that transaction
}
```

Two systems, no shared transaction. The database can commit and the publish
fail. ReconPilot records this honestly as defect D13.

**The standard fix is the transactional outbox:** write the event to an `outbox`
table in the *same* transaction, and a separate process publishes from there.
One commit, one truth, eventual publication.

---

## 10. Idempotency

**In any distributed system, retries are guaranteed.** Timeouts, at-least-once
delivery, an impatient user double-clicking. So every operation that changes
state must be safe to repeat.

### How

```sql
INSERT INTO ingestion_batch (id, tenant_id, content_hash, status)
VALUES (?, ?, ?, 'RECEIVED')
ON CONFLICT (tenant_id, content_hash) DO UPDATE
    SET status = ingestion_batch.status
RETURNING id;
```

**The key ideas:**
1. A **natural or supplied idempotency key** — here, a SHA-256 of the file
   content, so the same file is recognised no matter who uploads it.
2. A **unique constraint** in the database, because the application check is
   racy.
3. **One atomic statement**, not check-then-act.

ReconPilot's check-then-act version failed **12 of 14 concurrent batches**. The
window between the check and the act is where concurrency lives.

> **Interview question:** *"How do you make a payment API idempotent?"* — "The
> client sends an `Idempotency-Key` header. I store it with the result in a
> table with a unique constraint, inside the same transaction as the payment.
> A repeat with the same key returns the stored result rather than charging
> again. The critical part is that the uniqueness is enforced by the database,
> not by a lookup in application code — otherwise two concurrent retries both
> see 'not found' and both charge."

---

## 11. Backpressure and failure

### Bounded queues

```java
executor.setQueueCapacity(10);
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
```

**An unbounded queue turns overload into an `OutOfMemoryError`.** A bounded one
turns it into a **503**, which a client can retry and an operator can see.

Measured on this project: capacity 14, sixteen concurrent uploads → 14 accepted,
2 rejected with 503. That is the system telling the truth about its capacity.

### Timeouts everywhere

**A call with no timeout is a resource leak waiting for a bad day.** One slow
dependency exhausts your thread pool, and your service goes down because
someone else's did.

### Retries, with care

```
attempt 1 → wait 1s  → attempt 2 → wait 2s → attempt 3 → wait 4s
            + jitter (randomness) so clients don't retry in lockstep
```

**Only retry idempotent operations**, and only retryable errors. Retrying a 400
forever is waste; retrying a timeout is correct.

### Circuit breaker

```
CLOSED ──failures exceed threshold──▶ OPEN ──after a cooldown──▶ HALF-OPEN
   ▲                                                                │
   └──────────────────── success ───────────────────────────────────┘
```

When a dependency is clearly down, **stop calling it**. Fail fast, recover
quickly, and stop making its outage into your outage.

### Graceful degradation

Decide in advance what to turn off. ReconPilot keeps **claim filing disabled**
until the rounding rule is confirmed — drafting, reviewing and withdrawing all
work; only the irreversible outward step is gated.

> That is a good design instinct to articulate: *when you are not sure, disable
> the irreversible part and keep the rest working.*

---

## 12. Multi-tenancy

| Model | Isolation | Cost | Operations |
|---|---|---|---|
| Database per tenant | strongest | high | N migrations |
| Schema per tenant | strong | medium | N schemas |
| **Shared tables + `tenant_id`** | weakest by default | low | one migration |

ReconPilot uses the third — **and pushes the isolation into the database** with
row-level security, which removes the weakness:

```sql
ALTER TABLE recon_break ENABLE ROW LEVEL SECURITY;
ALTER TABLE recon_break FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON recon_break
    USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

> **Why this is the interesting answer:** "With a shared-table model, isolation
> normally depends on every query remembering `WHERE tenant_id = ?`. You cannot
> prove you never forgot. Moving the filter into the database makes it
> structural — a forgotten clause returns nothing rather than someone else's
> data. It cost us a least-privilege role and a session variable per connection,
> and it converts a class of catastrophic bug into an impossibility."

---

## 13. Observability

### The three pillars

- **Logs** — what happened. Structured (JSON), with a correlation id.
- **Metrics** — how much, how often, how slow. Cheap, aggregatable.
- **Traces** — one request's path across services.

### What to actually alert on

Alert on **symptoms users feel**, not on causes:

```
✅ error rate > 1% for 5 minutes
✅ p99 latency > 2s
✅ consumer lag growing steadily
✅ no successful backup in 48 hours

❌ CPU > 80%            — might be fine
❌ a single 500         — noise
```

### The failure mode of background systems is silence

Backups, queues, and scheduled jobs fail **quietly**. Nothing asks for them, so
nothing notices when they stop.

ReconPilot's backup container reports **unhealthy** if no backup has succeeded
in twice the interval — turning silence into a signal something can alert on.
And it honestly records (D14) that **nothing alerts on Kafka consumer lag yet**,
which is the same gap in a different place.

> Naming a gap you have not closed is more credible than claiming full
> observability.

---

## 14. Walking through ReconPilot

Practise this out loud. It is your system, and you can go arbitrarily deep.

### The problem

India's UPI MDR framework takes effect 15 October 2026. Merchants will start
paying a fee that did not exist. The processor computes it, charges it, and
reports it. **There is no independent check.** ReconPilot is the second opinion.

### Requirements

- Functional: ingest settlement files, recompute the expected fee, compare,
  record differences, drive them to recovery.
- Non-functional: **correctness above everything**; durability absolute;
  availability modest; ~0.4 writes/second average with million-row bursts.

### The design

```
Merchant
   │  settlement file (up to 2 GB)
   ▼
nginx (TLS termination, SPA, /api proxy)
   │
   ▼
Spring Boot API ──202 Accepted──▶ Merchant
   │  stage to disk (claim check), publish batch id
   ▼
Kafka ──▶ ingestion worker ──▶ PostgreSQL (append-only event log)
   │
   └────▶ reconciliation worker ──▶ breaks ──▶ disputes ──▶ React console
```

### The decisions worth defending

| Decision | Why | Cost accepted |
|---|---|---|
| **Modular monolith, not microservices** | 0.4 writes/sec; one team | one deployable unit |
| **Money as `BIGINT` paise** | floats accumulate error | format at the edge |
| **Append-only event log** | a regulator asks "why?" | more storage |
| **202 + queue** | 2 GB parse cannot live in a request | eventual consistency |
| **Kafka over an in-memory queue** | work must survive a crash | a broker to operate |
| **RLS in the database** | a forgotten `WHERE` is a breach | a second role, session state |
| **JWT, not sessions** | stateless app servers | cannot revoke before expiry |
| **Rules engine with no Spring** | 16 tests in 74 ms | a little plumbing |
| **Claim filing disabled** | rounding rule unconfirmed | the feature is dark |

### What is deliberately not done

One Kafka broker (RF 1, so broker loss is data loss), backups that never leave
the host, no alerting, single machine. **All recorded as defect D16.**

> Being able to say *"here is what I did not build, and why that is currently
> acceptable"* is the single most senior-sounding thing in a design interview.

---

## 15. What to say when you don't know

You will be asked something outside your experience. The answer is never to
bluff — interviewers detect it instantly and it costs more than the gap would
have.

**Good patterns:**

> "I haven't operated Cassandra. I know it is AP with tunable consistency and
> that the data model is driven by the query rather than by normalisation. If I
> were evaluating it I would start by asking whether we actually need write
> throughput one Postgres node cannot give, because that is the main reason to
> take on that operational cost."

> "I would need to measure before answering. My instinct is that the bottleneck
> is the database rather than the app tier, because the app is stateless and
> trivially scalable. I would check query time and connection pool saturation
> first."

> "Let me state my assumptions, because they drive the design: roughly a million
> writes a day, reads ten times that, and staleness of a minute is acceptable.
> If any of those is wrong the answer changes — is that roughly right?"

**Three habits that make you look senior:**

1. **Estimate before designing.** Numbers decide the architecture.
2. **Name the trade-off of every choice.** "This buys X and costs Y."
3. **Say what you did not build.** Every real system has gaps; only a
   pretending one has none.

---

## What to do next

1. **Rehearse the ReconPilot walkthrough out loud**, timed to five minutes. Not
   read — spoken. It is completely different.
2. **Design one thing a week** on paper: a URL shortener, a rate limiter, a
   notification service. Always start with estimation.
3. **For each decision in the table above, write the counter-argument.** Being
   able to argue the other side is what turns a memorised answer into a
   discussion.

Next: `12-redis.md`.
