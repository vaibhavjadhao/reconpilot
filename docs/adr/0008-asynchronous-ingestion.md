# ADR 0008: Accept uploads asynchronously with bounded capacity

**Status:** Accepted
**Date:** 2026-09-20
**Resolves:** D2 in KNOWN-DEFECTS.md

## Context

Ingesting 2,000,000 rows took ~51 seconds inside a single HTTP request. The
problem is not speed:

- Proxies and load balancers time out at 30-60 seconds, killing the request
  after most of the work is done.
- The client learns nothing until the end -- no id, no progress, nothing to poll.
- A dropped connection strands the batch in PARSING with nobody to notice.
- One upload occupies a Tomcat request thread for a minute. Tomcat's pool is
  bounded (200 by default), so a handful of concurrent uploads stops the API
  answering anything at all, including health checks.

The last point turns a slow feature into an outage.

## Decision

Split ingestion into a synchronous part and an asynchronous one.

**Synchronously**, on the request thread: validate the header, hash the file,
check idempotency, insert the batch row, and return **202 Accepted** with the
batch id and a `Location` header. 200 would be a lie -- it claims a result the
caller does not have.

**Asynchronously**, on a bounded pool: stream, parse and write, recording
PARSING -> PARSED or FAILED on the batch row. Nobody holds a connection to
receive an exception, so the outcome must be persisted where it can be polled.

The worker lives in a **separate bean**. `@Async` is proxy-based, so a call from
one method of a bean to another method of the same bean bypasses the proxy and
runs synchronously with no warning. The same trap applies to `@Transactional`
and `@Cacheable`.

**Every bound is explicit:** 2 core threads, 4 max, queue of 10.
`AbortPolicy` is chosen over `CallerRunsPolicy` deliberately -- CallerRuns hands
the work back to the HTTP thread, silently restoring the synchronous behaviour
this ADR removes, at the exact moment the system is busiest. Aborting surfaces
overload honestly as **503** and lets the client retry.

`StartupRecovery` marks batches left in RECEIVED or PARSING as FAILED at boot,
since their in-process state died with the previous JVM.

## Two defects this exposed

Both were found by testing the behaviour rather than by review, and both were
invisible before ingestion became concurrent.

**1. A rejected submission poisoned its own retry.** The batch row is written
before the work is queued, so a rejection left it stranded in RECEIVED. The
idempotency check keys on `content_hash`, so that stranded row made every
subsequent retry of the file report "already ingested" -- converting an honest
503 into silent, permanent data loss. Fixed by deleting the batch row on
rejection, leaving state exactly as it was before the request. The idempotency
check also now excludes FAILED batches, so a transient failure cannot block a
file forever.

**2. Merchant creation was a check-then-act race.** `SELECT`, then `INSERT` if
absent, has a window in which another thread inserts the same merchant; the
second INSERT then violates the unique constraint and fails the entire batch.
Sequential ingestion of 2,000,000 rows never hit it. The first concurrent burst
failed 12 of 14 batches. Fixed with an atomic `INSERT ... ON CONFLICT ... DO
UPDATE ... RETURNING id`, letting PostgreSQL resolve the race.

## Measured results

| | Before | After |
|---|---|---|
| POST response time | 51,018 ms | **657 ms** |
| Burst of 16 concurrent uploads | n/a | 14 accepted, 2 rejected with 503 |
| Batches stranded after rejection | 2 | **0** |
| Batches failed under concurrency | 12 of 14 | **0 of 14** |

## Consequences

- Clients poll `GET /api/ingest/{batchId}` instead of holding a connection.
- Capacity is finite and says so. Rejecting at the door is better than accepting
  work that will fail later as an OutOfMemoryError far from its cause.
- **State still lives inside the JVM.** A restart mid-file loses the work;
  `StartupRecovery` makes that visible but cannot resume it. A durable queue
  outside the process is the remaining gap, and is what a message broker would
  actually buy us here -- not throughput, which at 0.4 writes/second average we
  do not need.
