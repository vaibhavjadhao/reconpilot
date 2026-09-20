package in.reconpilot.messaging;

import java.util.UUID;

public record ReconciliationRequested(UUID batchId, UUID tenantId) {}
