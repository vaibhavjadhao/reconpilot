-- Closes defect D1: row-level security was enabled but never enforced.
--
-- Two separate reasons it did nothing:
--
--   1. PostgreSQL exempts a table's OWNER from RLS unless FORCE is also set.
--   2. A SUPERUSER bypasses RLS entirely, even when FORCE is set.
--
-- The application connected as `reconpilot`, which is both. So every policy
-- was inert, and 1,000,000 rows were visible with no tenant context at all.
--
-- The fix therefore needs both halves: force RLS on the tables, AND run the
-- application as a least-privilege, non-superuser role.

-- ---------------------------------------------------------------- users --

CREATE TABLE app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenant(id),
    email         TEXT        NOT NULL,
    -- BCrypt hash. A plaintext or unsalted-hash password column is a breach
    -- waiting for someone else's database dump to be compared against it.
    password_hash TEXT        NOT NULL,
    role          TEXT        NOT NULL DEFAULT 'ANALYST',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (email)
);

CREATE INDEX idx_app_user_tenant ON app_user (tenant_id);

-- ------------------------------------------------- least-privilege role --

-- The runtime role. Not a superuser, not an owner, so RLS applies to it.
-- Migrations continue to run as the owner; only the application runs as this.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reconpilot_app') THEN
        CREATE ROLE reconpilot_app LOGIN PASSWORD 'localdev_app';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO reconpilot_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO reconpilot_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO reconpilot_app;

-- Tables created by later migrations must be reachable too.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO reconpilot_app;

-- ------------------------------------------------------- force policies --

-- FORCE makes RLS apply to the table owner as well. Combined with the
-- non-superuser role above, the policies finally have effect.
ALTER TABLE merchant          FORCE ROW LEVEL SECURITY;
ALTER TABLE ingestion_batch   FORCE ROW LEVEL SECURITY;
ALTER TABLE transaction_event FORCE ROW LEVEL SECURITY;
ALTER TABLE recon_break       FORCE ROW LEVEL SECURITY;
ALTER TABLE dispute           FORCE ROW LEVEL SECURITY;

-- The original policies had USING only, which governs reads. Without
-- WITH CHECK, a tenant could still INSERT or UPDATE rows carrying someone
-- else's tenant_id -- writable but invisible, which is worse than readable.
--
-- current_setting(..., true) returns NULL when the setting was never set,
-- and '' when it was set and then cleared. NULLIF collapses both to NULL, and
-- a NULL comparison is never true -- so "no tenant context" means "no rows",
-- which is the safe default. Without NULLIF, ''::uuid raises and every query
-- fails with a cast error instead of returning nothing.
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['merchant','ingestion_batch','transaction_event','recon_break','dispute']
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format($f$
            CREATE POLICY tenant_isolation ON %I
                USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
        $f$, t);
    END LOOP;
END
$$;

-- app_user is looked up during login, before any tenant is known, so it is
-- deliberately not tenant-scoped. It exposes only an email and a hash.
