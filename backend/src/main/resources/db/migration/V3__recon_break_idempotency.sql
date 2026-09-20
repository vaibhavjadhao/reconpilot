-- A reconciliation run must be repeatable without duplicating its findings.
-- Re-running is normal: after a restatement, after a rules change, or simply
-- because someone clicked twice.
--
-- As with ingestion, idempotency is enforced by the database rather than by
-- application logic that can be forgotten. One transaction yields at most one
-- open break.
CREATE UNIQUE INDEX uq_break_per_transaction
    ON recon_break (transaction_event_id);

-- Which ruleset produced this finding. Rules change; a break found under one
-- version is not the same claim as a break found under another.
ALTER TABLE recon_break ADD COLUMN ruleset_version TEXT NOT NULL DEFAULT 'v1';

-- Reconciliation reads a batch at a time.
-- (batch_id, id) rather than batch_id alone: reconciliation walks a batch
-- in id order using keyset pagination, so the index must support both.
CREATE INDEX idx_txn_batch ON transaction_event (batch_id, id);
