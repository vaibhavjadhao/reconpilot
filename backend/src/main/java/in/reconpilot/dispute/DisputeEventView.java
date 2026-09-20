package in.reconpilot.dispute;

import java.time.Instant;

public record DisputeEventView(
        String fromStatus,
        String toStatus,
        String actor,
        String note,
        Long recoveredPaise,
        Instant occurredAt
) {}
