package in.reconpilot.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * "A file has been accepted and needs processing."
 *
 * <p>Carries only what the consumer needs to do the work. Note it carries the
 * {@code recordedAt} chosen at acceptance rather than letting the consumer pick
 * one: every row from this file must share a single "when we learned this"
 * timestamp, and a redelivered message must produce the same value as the
 * first attempt.
 *
 * <p>{@code stagedPath} is a local filesystem path, which is correct for a
 * single node and wrong for a cluster -- a consumer on another machine could
 * not read it. The production form is an object-store key. Recorded as a known
 * limitation rather than pretended away.
 */
public record IngestionRequested(
        UUID batchId,
        UUID tenantId,
        String stagedPath,
        String originalName,
        Instant recordedAt
) {}
