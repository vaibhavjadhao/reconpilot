# Known defects

Defects found and deliberately not yet fixed, with the evidence. Recorded here
rather than fixed in passing, because each needs a considered change.

---

## D1. Row-level security is enabled but not enforced (SECURITY)

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

## D2. Ingestion runs synchronously inside the HTTP request

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
