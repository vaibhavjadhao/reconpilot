-- A break is a finding. A dispute is a claim made about that finding, and a
-- claim has a life: drafted, filed with the PSP, acknowledged, accepted or
-- rejected, and finally settled.

-- What we are actually claiming. Recorded at creation and never recalculated,
-- because a claim is an assertion made at a moment in time. If the ruleset
-- changes later, the claim we filed does not retroactively change.
ALTER TABLE dispute ADD COLUMN claimed_paise BIGINT NOT NULL DEFAULT 0;

-- The PSP's own ticket reference, once we have one.
ALTER TABLE dispute ADD COLUMN external_reference TEXT;

-- One open claim per finding. Filing the same break twice is not a second
-- claim, it is a duplicate -- and PSPs reject duplicates, which damages
-- credibility on the claims that are real.
CREATE UNIQUE INDEX uq_dispute_per_break ON dispute (break_id);

CREATE INDEX idx_dispute_status ON dispute (tenant_id, status, created_at DESC);

-- Append-only history of every state change.
--
-- The dispute row carries the CURRENT state; this carries how it got there.
-- Without it, "why was this rejected?" and "who filed it, and when?" are
-- unanswerable, and those are exactly the questions asked six months later when
-- money is in dispute.
--
-- Same reasoning as the transaction event log: store the facts, derive the
-- state. Here the state is also stored, for query convenience, but it is
-- reconstructable from these rows.
CREATE TABLE dispute_event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    dispute_id      UUID NOT NULL REFERENCES dispute(id),
    from_status     TEXT,                    -- NULL on creation
    to_status       TEXT        NOT NULL,
    actor           TEXT        NOT NULL,    -- who caused it
    note            TEXT,
    recovered_paise BIGINT,                  -- set only on settlement
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dispute_event_dispute ON dispute_event (dispute_id, occurred_at);
