package in.reconpilot.security;

import java.util.UUID;

/**
 * The tenant the current thread is acting for.
 *
 * <p>A ThreadLocal, because the tenant is established once per request (from
 * the caller's token) and then needed deep in the stack, where passing it
 * explicitly through every method signature would be impractical.
 *
 * <p>The cost of a ThreadLocal is that it must be cleared. A request thread is
 * returned to a pool and reused, so a value left behind becomes the next
 * request's tenant -- the exact leak this class exists to prevent. Clearing
 * happens in a finally block in {@code JwtAuthFilter}.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs work as a given tenant, restoring whatever was there before. */
    public static <T> T callAs(UUID tenantId, java.util.concurrent.Callable<T> work) throws Exception {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return work.call();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
