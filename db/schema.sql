-- ReconPilot schema, v1
--
-- Conventions enforced throughout:
--   * All money is BIGINT paise. Never float, never numeric-with-scale.
--   * The event log is append-only. Nothing is ever UPDATEd or DELETEd.
--   * Every projection is rebuildable from the event log alone.
--   * Every table carrying customer data has tenant_id and is RLS-protected.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------- tenancy --

-- A tenant is our paying customer.
CREATE TABLE tenant (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A merchant is an entity whose transactions we reconcile. One tenant may own
-- several: multiple VPAs, stores, or legal entities.
CREATE TABLE merchant (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    external_ref  TEXT NOT NULL,          -- VPA or merchant id at the PSP
    display_name  TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, external_ref)
);

-- --------------------------------------------------------------- ingestion --

-- One row per file or API pull. content_hash gives file-level idempotency:
-- re-uploading identical bytes is rejected by the unique constraint rather
-- than by application logic that someone can forget to write.
CREATE TABLE ingestion_batch (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenant(id),
    source_type   TEXT NOT NULL,          -- PSP_STATEMENT | MARKETPLACE_SETTLEMENT | BANK_STATEMENT
    source_name   TEXT NOT NULL,
    content_hash  TEXT NOT NULL,          -- SHA-256 of the raw bytes
    row_count     INTEGER,
    status        TEXT NOT NULL,          -- RECEIVED | PARSING | PARSED | FAILED
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, content_hash)
);

-- ------------------------------------------------------------- event log --

-- THE source of truth. Append-only.
--
-- Bi-temporal: occurred_at is when the payment happened; recorded_at is when
-- we learned of it. "What did we know on 5 March?" is a query filtered on
-- recorded_at, not occurred_at.
--
-- A restatement by the PSP arrives as a NEW row with the same
-- external_txn_id and a later recorded_at. The current view of a transaction
-- is the row with the greatest recorded_at; the historical view at time T is
-- the greatest recorded_at <= T. Nothing is mutated.
CREATE TABLE transaction_event (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenant(id),
    batch_id           UUID NOT NULL REFERENCES ingestion_batch(id),

    -- MDR attaches to the PAYEE. The payer's category is irrelevant.
    payee_merchant_id  UUID NOT NULL REFERENCES merchant(id),
    payer_ref          TEXT,                    -- opaque; we do not model payers

    external_txn_id    TEXT   NOT NULL,         -- RRN / UTR from the source
    amount_paise       BIGINT NOT NULL CHECK (amount_paise >= 0),

    txn_type           TEXT NOT NULL,           -- P2P | P2M | P2PM
    payment_rail       TEXT NOT NULL,           -- UPI_QR | UPI_INTENT | UPI_AUTOPAY | UPI_CREDIT_LINE
    payee_category     TEXT,                    -- STANDARD | CAPITAL_MARKETS | INDUSTRY_PROGRAM | EDUCATION

    -- What the PSP says it charged. NULL where the source does not disclose it.
    charged_mdr_paise  BIGINT CHECK (charged_mdr_paise IS NULL OR charged_mdr_paise >= 0),

    occurred_at        TIMESTAMPTZ NOT NULL,
    recorded_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The source row exactly as received. We never discard the original: every
    -- parse is a lossy interpretation, and disputes are argued from the source.
    raw                JSONB NOT NULL,

    -- Same transaction twice in one batch is a data error. The same
    -- transaction in a LATER batch is a legitimate restatement.
    UNIQUE (tenant_id, batch_id, external_txn_id)
);

CREATE INDEX idx_txn_merchant_period
    ON transaction_event (tenant_id, payee_merchant_id, occurred_at);
CREATE INDEX idx_txn_current_view
    ON transaction_event (tenant_id, external_txn_id, recorded_at DESC);
CREATE INDEX idx_txn_replay
    ON transaction_event (tenant_id, recorded_at);

-- ------------------------------------------------------------ projections --

-- Everything below is DERIVED. It can be dropped and rebuilt from
-- transaction_event at any time. It is cache, not truth.

-- Monthly inward totals, feeding the P2PM threshold test.
-- as_of records the recorded_at watermark used, so a projection built from
-- partial data is never mistaken for one built from complete data.
CREATE TABLE merchant_month_total (
    merchant_id   UUID NOT NULL REFERENCES merchant(id),
    month         DATE NOT NULL,            -- first day of the month
    inward_paise  BIGINT NOT NULL,
    qualifies     BOOLEAN NOT NULL,         -- inward_paise > 1,00,000 rupees
    as_of         TIMESTAMPTZ NOT NULL,
    computed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, month)
);

-- Category transitions (ADR 0004). Append-only: the category in force on any
-- past date is reconstructable, which a mutable column on merchant would not
-- allow.
CREATE TABLE merchant_category_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id    UUID NOT NULL REFERENCES merchant(id),
    from_category  TEXT,                    -- NULL on initial classification
    to_category    TEXT NOT NULL,           -- P2PM | P2M
    effective_from DATE NOT NULL,
    reason         TEXT NOT NULL,           -- e.g. '3 consecutive months > 1L'
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_category_lookup
    ON merchant_category_event (merchant_id, effective_from DESC);

-- What we computed, and CRUCIALLY which rule produced it. Three rules can all
-- yield zero for different reasons with different lifespans, so the reason is
-- as important as the number.
CREATE TABLE mdr_calculation (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_event_id  UUID   NOT NULL REFERENCES transaction_event(id),
    expected_mdr_paise    BIGINT NOT NULL CHECK (expected_mdr_paise >= 0),
    rule_id               TEXT   NOT NULL,   -- e.g. 'AUTOPAY_EXEMPT', 'STD_0P4_CAPPED'
    ruleset_version       TEXT   NOT NULL,   -- rules change; results must be attributable
    computed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (transaction_event_id, ruleset_version)
);

-- ---------------------------------------------------------------- breaks --

CREATE TABLE recon_break (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID   NOT NULL REFERENCES tenant(id),
    transaction_event_id  UUID   NOT NULL REFERENCES transaction_event(id),
    expected_mdr_paise    BIGINT NOT NULL,
    charged_mdr_paise     BIGINT NOT NULL,
    delta_paise           BIGINT NOT NULL,   -- charged - expected; > 0 = overcharged
    break_type            TEXT   NOT NULL,   -- OVERCHARGED | UNDERCHARGED | CHARGED_WHEN_EXEMPT | CAP_BREACHED
    status                TEXT   NOT NULL,   -- OPEN | DISPUTED | RESOLVED | WRITTEN_OFF
    detected_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_break_open ON recon_break (tenant_id, status, detected_at DESC);

CREATE TABLE dispute (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenant(id),
    break_id         UUID NOT NULL REFERENCES recon_break(id),
    status           TEXT NOT NULL,   -- DRAFT|FILED|ACKNOWLEDGED|ACCEPTED|REJECTED|RECOVERED
    recovered_paise  BIGINT CHECK (recovered_paise IS NULL OR recovered_paise >= 0),
    filed_at         TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ------------------------------------------------------- tenant isolation --

-- Defence in depth. Application code will also filter by tenant, but one
-- forgotten WHERE clause must not be able to leak another customer's data.
ALTER TABLE merchant                ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingestion_batch         ENABLE ROW LEVEL SECURITY;
ALTER TABLE transaction_event       ENABLE ROW LEVEL SECURITY;
ALTER TABLE recon_break             ENABLE ROW LEVEL SECURITY;
ALTER TABLE dispute                 ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON merchant
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
CREATE POLICY tenant_isolation ON ingestion_batch
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
CREATE POLICY tenant_isolation ON transaction_event
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
CREATE POLICY tenant_isolation ON recon_break
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
CREATE POLICY tenant_isolation ON dispute
    USING (tenant_id = current_setting('app.tenant_id')::uuid);
