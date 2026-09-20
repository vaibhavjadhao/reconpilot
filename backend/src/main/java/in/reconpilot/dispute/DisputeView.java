package in.reconpilot.dispute;

import java.time.Instant;
import java.util.UUID;

public record DisputeView(
        UUID id,
        UUID breakId,
        String status,
        long claimedPaise,
        Long recoveredPaise,
        String externalReference,
        Instant createdAt,
        Instant filedAt,
        Instant resolvedAt
) {}
