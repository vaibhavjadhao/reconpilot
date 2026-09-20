# ADR 0012: Authentication, and making tenant isolation actually work

**Status:** Accepted
**Date:** 2026-09-20
**Resolves:** D1

## Context

D1 recorded that row-level security was configured and inert: 1,000,000 rows
were visible with no tenant context at all. Nothing could be built safely on
top of that, so it was fixed before adding further features.

## Why the original RLS did nothing

Two independent exemptions, either of which is sufficient to disable it:

1. PostgreSQL exempts a table's **owner** from RLS unless `FORCE` is also set.
2. A **superuser** bypasses RLS entirely, even with `FORCE`.

The application connected as `reconpilot`, which was both. The policies were
syntactically correct and never consulted.

## Decisions

**Two roles.** Migrations and cross-tenant system tasks run as the owner.
The application runs as `reconpilot_app`: not the owner, not a superuser, so
policies apply. This is not hardening, it is a precondition -- with one
all-powerful connection, RLS cannot work at all.

**FORCE ROW LEVEL SECURITY** on every tenant-scoped table.

**Policies gained WITH CHECK.** The originals had `USING` only, which governs
reads. Without `WITH CHECK` a tenant could insert or update rows carrying
another tenant's id: writable but invisible, which is worse than readable.

**`NULLIF(current_setting('app.tenant_id', true), '')::uuid`.** Unset returns
NULL and cleared returns `''`; NULLIF collapses both to NULL, and a NULL
comparison is never true. No tenant context therefore means no rows, which is
the safe default. Without NULLIF, `''::uuid` raises and every query fails with
a cast error instead of returning nothing.

**Tenant comes from the signed token, never from a parameter.** A `?tenant=`
parameter would let anyone read anyone's data by editing a URL.

**JWT rather than sessions.** A session id makes the server remember every
logged-in user, which becomes shared state every instance needs. A signed token
carries its own proof. The cost is that it cannot be un-issued before it
expires, which is why the TTL is short.

**BCrypt for passwords.** Fast hashes are the wrong tool precisely because they
are fast. BCrypt is deliberately slow, tunable upward, and salts each password
so identical passwords produce different hashes.

**Login returns the same 401 for an unknown email and a wrong password.**
Distinguishing them turns the login form into a way to enumerate accounts.

**401, not 403, for an unauthenticated caller.** Spring Security's default
answers 403 to a caller with no credentials, which a client cannot distinguish
from "you lack permission".

## Connection pooling is the subtle part

A PostgreSQL session variable set on a pooled connection stays on it when it is
returned, so the next borrower would inherit the previous request's tenant. That
is a cross-tenant leak caused by an optimisation unrelated to tenancy.

`TenantAwareDataSource` therefore sets the tenant on every checkout *and* clears
it on close. Either would suffice in the normal path; both are present because
the failure is silent and severe.

The same applies to the `ThreadLocal`: request threads are pooled, so
`JwtAuthFilter` clears it in a finally block, and `IngestionWorker` -- running
on a different thread, where a ThreadLocal does not follow -- re-establishes it
from the tenant id it already receives as a parameter.

## Three defects found while doing this

**Declaring `adminJdbcTemplate` silently deleted the normal one.** Spring
Boot's auto-configured `JdbcTemplate` is `@ConditionalOnMissingBean(JdbcOperations.class)`,
so adding an admin template caused it to back off -- leaving the privileged
template as the only candidate and routing every controller through it. RLS was
working perfectly at the database level and applied to nothing, because no
application query ever reached it. **Adding a bean can remove another one.**

**The test suite destroyed the development database.** `@ServiceConnection`
only overrides `spring.datasource.*`; the hand-rolled `app.datasource.admin.*`
kept pointing at `localhost:5432/reconpilot`, and `clearDatabase()` ran
`TRUNCATE ... CASCADE` against a million rows of real data. Fixed by pointing
those properties at the container, and by making `AbstractIntegrationTest`
refuse to run unless its connection is demonstrably the test container. A
destructive operation should verify what it is about to destroy rather than
trust that the wiring is right.

**A custom DataSource bean disconnects `@ServiceConnection` entirely.**
Testcontainers contributes a `JdbcConnectionDetails` bean which Spring Boot's
*auto-configured* DataSource consumes. Replacing that DataSource with a
hand-built one built from `application.properties` silently ignores the
container. The bean now honours `JdbcConnectionDetails` when present.

## Consequences

- Verified: with two tenants holding 300 and 120 breaks, each sees only its own,
  neither sees a third tenant's 7,300, and knowing another tenant's row id
  grants nothing.
- Every service call now needs a tenant context, including tests. That is the
  same constraint production has, so tests exercise it rather than working
  around it.
- Cross-tenant work must be explicit, via the admin template. Being forced to
  name it is the point.
- The React client does not yet authenticate and will receive 401s until it is
  updated.
