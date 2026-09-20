package in.reconpilot.ingest;

import java.time.Instant;
import java.util.UUID;

public record BatchStatus(
        UUID batchId,
        String sourceName,
        String status,
        Long rowCount,
        Instant receivedAt,
        Instant startedAt,
        Instant completedAt,
        String errorMessage
) {}
