# Known defects

Defects found and deliberately not yet fixed, with the evidence. Recorded here
rather than fixed in passing, because each needs a considered change.

---

## D1. Row-level security is enabled but not enforced (SECURITY) ~~OPEN~~ RESOLVED

**Resolved 2026-09-20** by ADR 0012 and migration V6. Verified by
`TenantIsolationIT`. Original description retained below.

**Severity:** high. Multi-tenant data isolation does not work.

`V1__initial_schema.sql` enables RLS on the tenant-scoped tables and defines
policies keyed on `current_setting('app.tenant_id')`. ADR 0006 describes this as
defence in depth. **It is currently inert.**

PostgreSQL exempts a table's **owner** from row-level security unless
`FORCE ROW LEVEL SECURITY` is also set. The application connects as
`reconpilot`, which owns every table, so every policy is bypassed.

Measured:

```
 relname           | rls_enabled | rls_forced
 transaction_event | t           | f            <- enabled, not forced

 current_user: reconpilot        <- also the table owner

 SELECT count(*) FROM transaction_event;   -- no app.tenant_id set
 -> 2000000                                 <- all rows visible
```

A control that is enabled but not enforced is worse than none, because it
creates false confidence: the schema reads as though isolation is handled, and
a reviewer would believe it.

**The fix has three parts, all needed:**

1. `ALTER TABLE ... FORCE ROW LEVEL SECURITY` on every RLS table.
2. Connect as a **non-owner** application role with only DML rights. Ownership
   and runtime access should not be the same principal.
3. Set `app.tenant_id` per transaction, from the authenticated request, so the
   policies have a value to match. Ingestion currently sets nothing.

Part 3 requires authentication, which does not exist yet, so this is scheduled
with that work rather than patched now.

---

## D2. Ingestion runs synchronously inside the HTTP request ~~OPEN~~ RESOLVED

**Resolved 2026-09-20** by ADR 0008. POST now returns 202 in ~657 ms and the
work runs on a bounded pool. Original description retained below.

**Severity:** medium. Correctness is fine; the design is wrong.

Ingesting 2,000,000 rows takes ~51 seconds, held open inside one HTTP request.
Any proxy or load balancer will time out, the client cannot see progress, a
dropped connection leaves a batch stuck in `PARSING`, and one large upload
occupies a request thread for a minute.

**Fix:** accept the file, record the batch, return `202 Accepted` with the batch
id immediately, and process asynchronously. This is the next piece of work and
is where the message queue earns its place.

---

## D3. Error responses leak internal package names

**Severity:** low.

An invalid enum returns:

```json
{"message":"No enum constant in.reconpilot.mdr.TxnType.BANANA"}
```

This discloses internal structure to any caller. Error messages should describe
what the caller did wrong, not how the server is built.

---

## D4. Development ingestion endpoint accepts an arbitrary server path

**Severity:** high if ever deployed; currently dev-only.

`POST /api/ingest?path=...` reads any file the process can read. It exists to
exercise ingestion locally and must be removed or restricted before any
deployment. The production route is a streamed multipart upload or an
object-store key.

---

## D5. In-process work does not survive a restart

**Severity:** medium. Introduced by ADR 0008.

Ingestion state lives in a thread pool inside the JVM. A deploy, crash or OOM
mid-file loses the work. `StartupRecovery` marks such batches FAILED so they are
visible rather than stuck, but it cannot resume them and the caller is not told.

**Fix:** move the queue outside the process. This, rather than throughput, is
what a message broker buys at this scale.

---

## D6. Reconciliation runs synchronously inside the HTTP request

**Severity:** low for now, medium as batches grow. Same shape as D2.

`POST /api/recon/{batchId}` scans the batch on the request thread. At 3 seconds
for 1,000,000 rows this is tolerable, but the duration scales with batch size
and it will eventually hit the same proxy timeouts that D2 did.

**Fix:** reuse the pattern from ADR 0008 -- 202 Accepted, a bounded pool, and a
pollable status.

---

## D7. False breaks are possible until the rounding rule is confirmed

**Severity:** high for correctness of customer-facing claims.

Demonstrated, not theoretical. A one-paise rounding disagreement between two
implementations produced **8,174 false breaks** in a single 1,000,000-row run
(see ADR 0009). Our HALF_UP choice is provisional pending open question 5.

If a real PSP rounds differently from us, every affected transaction becomes a
false claim. **No breaks should be filed against a live PSP until the rounding
rule is confirmed from the NPCI circular**, or until the engine is calibrated
against a sample of that PSP's actual charges.

---

## D8. The unique constraint disagreed with the retry logic ~~OPEN~~ RESOLVED

**Resolved 2026-09-20** by migration V4. Found by
`IngestionIT.aFailedBatchCanBeRetried` on its very first run.

ADR 0008 changed the idempotency *query* to ignore FAILED batches so a
transient error could not block a file forever. The *constraint* on
`ingestion_batch (tenant_id, content_hash)` was left untouched, so the
application decided to retry and PostgreSQL refused with a duplicate key.

Fixed with a partial unique index -- `WHERE status <> 'FAILED'` -- so the
constraint now states what the code means: at most one non-failed batch per
file, any number of failed attempts.

The lesson is that application logic and database constraints encode the same
rule in two places, and changing one without the other produces a system that
contradicts itself.

---

## D9. The build depended on the developer's shell ~~OPEN~~ RESOLVED

**Resolved 2026-09-20.** Two separate causes, both meaning `mvn test` passed in
a terminal and failed in IntelliJ.

**A context test in the unit phase.** Spring Initializr generates
`BackendApplicationTests`, which starts the whole application and therefore a
database container -- but the `*Tests` name puts it in Surefire's fast phase, so
`mvn test` required Docker. A test that needs a database is not a unit test.
Renamed to `ApplicationContextIT` so Failsafe runs it.

**Lombok breaking on JDK 27.** `JAVA_HOME` was set in `~/.zprofile`, which only
zsh *login* shells read. IntelliJ runs Maven via `/bin/sh`, so it fell back to
JDK 27, where Lombok's annotation processor crashes with
`ExceptionInInitializerError: com.sun.tools.javac.tree.EndPosTable` -- it reaches
into private compiler internals, so new JDKs break it until it catches up.

Lombok was never used anywhere in `src/`; it came in from the Initializr
checkbox. Removing it eliminates the failure entirely rather than pinning
around it, and the build now succeeds on both JDK 21 and JDK 27.

The general shape is worth remembering: **configuration that lives in a shell
profile is invisible to anything that is not that shell** -- not `/bin/sh`, not
an app launched from the Dock, not a CI runner. A build that only works because
of your shell is not reproducible.

---

## D10. The frontend does not authenticate ~~OPEN~~ RESOLVED

**Resolved 2026-09-20.** Login and register screens, token in a Redux slice
persisted to localStorage, `prepareHeaders` attaching the bearer token from one
place, and a base-query wrapper that signs the user out on any 401.

**Severity:** blocking for the UI, introduced by ADR 0012.

Every API call except `/api/auth/**` now requires a bearer token. The React
console sends none and receives 401. It needs a login screen, token storage,
an RTK Query `prepareHeaders` that attaches the token, and a redirect to login
on 401.
