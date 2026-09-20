package in.reconpilot.ingest;

import java.util.UUID;

/**
 * The immediate answer to an upload: what we will do, not what we did.
 *
 * @param batchId     poll this for progress
 * @param status      RECEIVED when queued, PARSED when the file was already known
 * @param alreadySeen true when identical bytes had been ingested before
 */
public record IngestionSubmission(UUID batchId, String status, boolean alreadySeen) {}
