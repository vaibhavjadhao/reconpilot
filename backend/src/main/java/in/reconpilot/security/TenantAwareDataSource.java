package in.reconpilot.security;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Applies the current tenant to every connection handed out, so PostgreSQL's
 * row-level security policies have a value to match.
 *
 * <h2>Why this is not as simple as running SET once</h2>
 *
 * Connections are pooled. A PostgreSQL session variable set on a connection
 * stays on that connection when it is returned to the pool, so the next request
 * to borrow it would inherit the previous request's tenant. That is not a
 * theoretical risk: it is a cross-tenant data leak caused by an optimisation
 * that has nothing to do with tenancy.
 *
 * <p>Two defences, deliberately overlapping:
 *
 * <ul>
 *   <li><b>Set on every checkout.</b> Whatever the previous borrower left is
 *       overwritten before the connection is used, including being cleared when
 *       there is no tenant.
 *   <li><b>Clear on close.</b> The connection returns to the pool carrying
 *       nothing.
 * </ul>
 *
 * <p>Either alone would be sufficient in the normal path. Both are present
 * because the failure mode is silent and severe, and the cost is one extra
 * statement.
 *
 * <p>{@code set_config} is used with a bound parameter rather than string
 * concatenation. The value is a UUID and could not carry an injection, but
 * building SQL by concatenation is a habit worth not having.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(super.getConnection(username, password));
    }

    private Connection wrap(Connection connection) throws SQLException {
        applyTenant(connection, TenantContext.get());
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ClearOnClose(connection));
    }

    private static void applyTenant(Connection c, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SET_TENANT)) {
            // Empty string rather than null: the policies use NULLIF to treat
            // '' as "no tenant", which matches no rows.
            ps.setString(1, tenantId == null ? "" : tenantId.toString());
            ps.execute();
        }
    }

    /** Clears the tenant before the connection goes back to the pool. */
    private record ClearOnClose(Connection target) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("close".equals(method.getName()) && !target.isClosed()) {
                try {
                    applyTenant(target, null);
                } catch (SQLException ignored) {
                    // A connection being discarded because it is already broken
                    // must not turn into a failure on the way out.
                }
            }
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
