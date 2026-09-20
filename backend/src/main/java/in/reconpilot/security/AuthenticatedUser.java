package in.reconpilot.security;

import java.util.UUID;

/** Who is making this request, and for which tenant. */
public record AuthenticatedUser(UUID userId, UUID tenantId, String email, String role) {}
