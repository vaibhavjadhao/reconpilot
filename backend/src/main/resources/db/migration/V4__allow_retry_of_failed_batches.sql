-- V1 declared UNIQUE (tenant_id, content_hash) on ingestion_batch, which made
-- re-uploading identical bytes impossible. That is right for a batch that
-- succeeded or is in flight, and wrong for one that FAILED: a transient error
-- would block a file forever.
--
-- ADR 0008 changed the application's idempotency query to ignore FAILED
-- batches, but left this constraint untouched, so the code decided to retry
-- and the database refused. Caught by IngestionIT.aFailedBatchCanBeRetried.
--
-- A PARTIAL unique index makes the constraint say what the code means: at most
-- one non-failed batch per file, and any number of failed attempts.

ALTER TABLE ingestion_batch
    DROP CONSTRAINT ingestion_batch_tenant_id_content_hash_key;

CREATE UNIQUE INDEX uq_batch_content_not_failed
    ON ingestion_batch (tenant_id, content_hash)
    WHERE status <> 'FAILED';
