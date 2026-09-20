package in.reconpilot.ingest;

import java.util.UUID;

/**
 * @param batchId      the ingestion_batch row this file became
 * @param rowsIngested rows written (zero when the file was already known)
 * @param alreadySeen  true when an identical file had been ingested before
 * @param millis       wall-clock duration
 */
public record IngestionResult(UUID batchId, long rowsIngested, boolean alreadySeen, long millis) {}
