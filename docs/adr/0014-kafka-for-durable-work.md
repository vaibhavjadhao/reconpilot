# ADR 0014: Kafka as a durable queue, not for throughput

**Status:** Accepted
**Date:** 2026-09-20
**Resolves:** D5, D6

## Context

Ingestion ran on an in-process thread pool. A restart lost accepted work:
`StartupRecovery` marked it FAILED so it was visible, but nothing could resume
it. Reconciliation was still synchronous inside the request (D6).

## What Kafka is and is not being adopted for

Average write rate is **0.4 per second**. Kafka is not here for throughput, and
claiming otherwise would be cargo-cult architecture. It is here for three
specific properties:

- **Durability of the job.** The queue outlives the process working on it.
- **Redelivery.** A consumer that dies mid-record has its message handed back.
- **Replay.** Offsets rewind, so a day's ingests can be reprocessed after a
  rules change.

## Decisions

**KRaft mode, no ZooKeeper**, and the image pinned to `apache/kafka:4.3.1` --
the same reasoning as pinning PostgreSQL.

**Three partitions, keyed by tenant.** Kafka orders within a partition only, so
one tenant's uploads stay ordered while different tenants proceed in parallel.
Partition count is also the ceiling on useful consumer concurrency.

**Topics are declared, not auto-created.** With auto-creation on, a typo in a
topic name silently creates a new topic: the producer succeeds, the consumer
waits forever, and nothing reports an error.

**`enable-auto-commit=false`, ack mode `record`.** This is the single most
important consumer setting. With auto-commit, Kafka commits offsets on a timer
whether or not the record was processed -- a crash in between means the message
is gone, never processed and never redelivered. Committing only after the
listener returns is what makes crash recovery work at all.

**Publishing waits for the broker's acknowledgement.** `KafkaTemplate.send`
returns a future; without joining it, the request would answer 202 while the
publish was still in flight, and a rejection would surface as a log line nobody
reads.

**An error handler with a dead-letter topic.** Without one, Spring retries a
failing record forever: the offset never advances, the partition stops, and
every message behind it waits. That is the poison-message failure mode, and it
is how Kafka consumers most often fall over. Three attempts, then the record
goes to a DLT and the partition keeps flowing.

## What testing a real crash exposed

A `kill -9` partway through a 400,000-row file, then a restart:

```
upload            PARSING, 171,000 rows
kill -9           176,000 rows written, batch stuck PARSING
restart           Kafka rebalances (~40s) and redelivers
resumed           238,000 -> 400,000 rows, PARSED
```

Getting there took two fixes that only a real crash reveals.

**The consumer was not idempotent at the right level.** At-least-once delivery
means a redelivered batch is reprocessed with its earlier rows still present,
and the unique constraint rejected the whole retry with a duplicate-key error.
A crashed ingestion could therefore never finish: redelivered forever, failing
identically each time. File-level idempotency (the content hash) does not help
-- that stops one file becoming two batches, a different question from one
batch being written twice. Fixed with `ON CONFLICT DO NOTHING`, which also
makes a resumed batch continue from where it stopped.

**`StartupRecovery` had become harmful.** Marking every PARSING batch FAILED at
startup was right when state lived in a thread pool; with a broker holding the
message it contradicts what is about to happen. Narrowed to RECEIVED batches
past a grace period -- see D13.

## Consequences

- Accepted work survives a process kill.
- Overload no longer produces 503. The thread pool rejected when full, which
  was honest and immediate; a queue absorbs the burst and the symptom becomes
  consumer lag instead. Lag is the thing to alert on now, and nothing yet does.
- `stagedPath` in the message is a local filesystem path: correct for one node,
  wrong for a cluster, where a consumer on another machine could not read it.
  The production form is an object-store key.
- Accepting an upload writes a database row and then publishes -- two systems,
  one logical operation, no shared transaction. See D13.
