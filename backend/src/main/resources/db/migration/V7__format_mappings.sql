-- Onboarding a new PSP means understanding a file whose column names nobody
-- has seen before: txn_ref, "Transaction ID", UTR No., RRN. The space of
-- possible headers is open-ended, which is exactly the kind of problem that
-- does not generalise in code.
--
-- A mapping is discovered ONCE per format and then reused. The key is a
-- fingerprint of the header row: same headers, same mapping, no model call.
-- A million-row file therefore costs zero API calls after the first.

CREATE TABLE format_mapping (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenant(id),

    -- SHA-256 of the normalised header row. Identifies the FORMAT, not the file.
    header_fingerprint  TEXT        NOT NULL,
    source_label        TEXT        NOT NULL,
    header_row          TEXT        NOT NULL,

    -- canonical field -> source column name
    column_map          JSONB       NOT NULL,
    -- PAISE or RUPEES: whether the source's amounts need scaling by 100
    amount_unit         TEXT        NOT NULL,
    -- source value -> canonical enum value, e.g. "QR" -> "UPI_QR"
    value_aliases       JSONB       NOT NULL DEFAULT '{}'::jsonb,

    -- PROPOSED once the model has answered; VALIDATED only after the mapping
    -- has been checked against real rows; REJECTED when it failed that check.
    status              TEXT        NOT NULL DEFAULT 'PROPOSED',
    model               TEXT,
    confidence          NUMERIC(4,3),
    validation_notes    TEXT,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    validated_at        TIMESTAMPTZ,

    -- One mapping per format per tenant. A second discovery for the same
    -- headers is a cache hit, not a new row.
    UNIQUE (tenant_id, header_fingerprint)
);

CREATE INDEX idx_format_mapping_lookup ON format_mapping (tenant_id, header_fingerprint);

ALTER TABLE format_mapping ENABLE ROW LEVEL SECURITY;
ALTER TABLE format_mapping FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON format_mapping
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON format_mapping TO reconpilot_app;
