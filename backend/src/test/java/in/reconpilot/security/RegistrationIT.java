package in.reconpilot.security;

import in.reconpilot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registering the same email twice used to return 500 and leave a tenant row
 * behind that no user would ever belong to.
 *
 * <p>Found by re-running {@code ops/demo/seed-demo.sh}, which registers the
 * same address every time on purpose so that it can be run again after a
 * redeploy. Two runs produced two orphaned tenants, and nothing anywhere
 * reported it -- no error, no log line, no constraint. The rows simply
 * accumulated.
 *
 * <p>Both halves are tested, because they are separate mistakes that happened
 * to share a line of code: the wrong status, and the partial write.
 */
class RegistrationIT extends AbstractIntegrationTest {

    @Autowired AuthController auth;

    private static AuthController.RegisterRequest request(String email) {
        return new AuthController.RegisterRequest("Acme Ltd", email, "Str0ng-Passw0rd!");
    }

    @Test
    @DisplayName("a first registration creates one tenant and one user")
    void firstRegistrationSucceeds() {
        auth.register(request("first@example.com"));

        assertEquals(1, count("tenant"));
        assertEquals(1, count("app_user"));
    }

    @Nested
    @DisplayName("registering an email that already exists")
    class Duplicate {

        @Test
        @DisplayName("is rejected with 409, not 500")
        void isAConflict() {
            auth.register(request("taken@example.com"));

            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> auth.register(request("taken@example.com")));

            // 500 would tell the caller the server broke and that retrying
            // might help. Neither is true.
            assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        }

        @Test
        @DisplayName("leaves no tenant behind")
        void writesNothing() {
            auth.register(request("taken@example.com"));
            long tenantsAfterFirst = count("tenant");

            assertThrows(ResponseStatusException.class,
                    () -> auth.register(request("taken@example.com")));

            // The regression. Two inserts without a transaction committed the
            // first one before the second could fail, so this was 2.
            assertEquals(tenantsAfterFirst, count("tenant"),
                    "a failed registration must not commit the tenant row");
            assertEquals(0, orphanTenants(), "no tenant should exist without a user");
        }
    }

    private long orphanTenants() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM tenant t
                 WHERE NOT EXISTS (SELECT 1 FROM app_user u WHERE u.tenant_id = t.id)
                """, Long.class);
    }
}
