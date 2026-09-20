# ADR 0007: Stream ingestion, and write with JDBC batching rather than JPA

**Status:** Accepted
**Date:** 2026-09-20

## Context

Settlement files are large. The product promise is files up to 2 GB.

Measured on a 148 MB, 2,000,000-row CSV with the heap capped:

| Heap | Read whole file into memory | Stream line by line |
|---|---|---|
| 256 MB | 251 MB used -- survived by 5 MB | 65 MB |
| 128 MB | **OutOfMemoryError** | 80 MB |
| 96 MB | **OutOfMemoryError** | 24 MB |
| 32 MB | -- | 23 MB, succeeded |

Reading the file whole costs roughly 2.7x its size on disk, because each line
becomes a String object carrying about 40 bytes of overhead on top of its
characters. At 256 MB it *worked*, which is the dangerous part: it would pass
review and fail later, in production, on the largest file from the largest
customer.

## Decision

**Parse and hash as streams.** Memory use must be independent of input size.
`SettlementCsvParser` returns a lazy `Stream<SettlementRow>` tied to an open
file handle; the SHA-256 is computed over an 8 KB buffer. Nothing accumulates.

**Write with `JdbcTemplate` batching, not JPA.** JPA manages the lifecycle of a
graph of mutable domain objects. These rows are immutable facts written once and
never updated. An ORM would add per-row overhead and a persistence context that
grows with the file, reintroducing the memory problem streaming just solved.
Rows are buffered to 1,000 and flushed; the buffer is the only thing that grows
and it is bounded.

**One `recorded_at` per batch.** Every row from one file shares a single "when
we learned this" timestamp, so a replay filtered on `recorded_at` includes the
whole file or none of it. Calling `now()` per row would smear one file across
time and make historical reconstruction ambiguous.

**Idempotency by database constraint.** The file's SHA-256 is stored on
`ingestion_batch` under `UNIQUE (tenant_id, content_hash)`. Re-uploading
identical bytes is rejected by PostgreSQL, not by application logic that can be
forgotten or refactored away.

**Enable `reWriteBatchedInserts=true`** on the JDBC URL. The PostgreSQL driver
collapses a batch into one multi-row INSERT rather than N single-row ones.

## Measured results

2,000,000 rows, heap capped at 256 MB:

| | Time | Rows/sec |
|---|---|---|
| JDBC batching | 70.8 s | ~28,000 |
| ...plus `reWriteBatchedInserts` | **51.0 s** | **~39,000** |

Re-uploading the identical file: 652 ms, 0 rows written.

## Consequences

- A 2 GB file costs the same heap as a 2 MB one.
- Storage is larger than estimated in ADR 0002: **456 bytes/row measured**
  against 300 estimated. 522 MB of table plus 349 MB of indexes for 2M rows.
  Indexes are 40% of the total, which the original estimate omitted entirely.
  Revised: roughly 5.5 GB/year at 12M rows/year, still comfortably within the
  single-instance conclusion ADR 0002 reached. The estimate was wrong in detail
  and right in decision, which is what a back-of-envelope estimate is for.
- Losing JPA for this table means hand-written SQL and no compile-time checking
  of column names. Accepted: the insert is one statement in one place.
- Ingestion is still synchronous inside the HTTP request, which is wrong for a
  70-second operation. Making it asynchronous is the next step.
