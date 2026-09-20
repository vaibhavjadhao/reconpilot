package in.reconpilot.ingest;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of the synchronous part of ingestion.
 *
 * @param recordedAt one value for the whole file, so a replay filtered on it
 *                   includes the entire batch or none of it
 */
public record PreparedBatch(UUID batchId, Instant recordedAt, boolean alreadySeen) {}
