-- Ingestion becomes asynchronous, so a batch now has a lifecycle that outlives
-- the request that started it. These columns record how it went, since nobody
-- is holding a connection open to find out.

ALTER TABLE ingestion_batch
    ADD COLUMN started_at    TIMESTAMPTZ,
    ADD COLUMN completed_at  TIMESTAMPTZ,
    ADD COLUMN error_message TEXT;

-- Finding work that was interrupted by a restart.
CREATE INDEX idx_batch_status ON ingestion_batch (status, received_at);
