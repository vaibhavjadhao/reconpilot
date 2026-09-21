package in.reconpilot.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record LoginRequest(String email, String password) {}
    public record LoginResponse(String token, long expiresInSeconds, String email, String role) {}
    public record RegisterRequest(String tenantName, String email, String password) {}

    private final JdbcTemplate admin;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthController(@Qualifier("adminJdbcTemplate") JdbcTemplate admin,
                          PasswordEncoder encoder, JwtService jwt) {
        // Login happens before any tenant is known, so it cannot run on the
        // tenant-scoped connection: RLS would hide the very row being sought.
        this.admin = admin;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    /**
     * Creates a tenant and its first user. Demo convenience, not a real signup.
     *
     * <p>One statement, not two, and that is load-bearing. It used to be two
     * separate inserts with no transaction around them, so when the second one
     * hit the unique index over email the first had already committed: every
     * duplicate registration left behind a tenant that no user would ever
     * belong to. A leak nothing reads, nothing cleans up and nothing reports.
     *
     * <p>A CTE makes both inserts one statement, and a statement in PostgreSQL
     * is atomic on its own -- if the user insert fails, the tenant insert is
     * undone with it. That avoids introducing a second transaction manager
     * purely to make the admin connection transactional, which would be a lot
     * of machinery for two rows.
     *
     * <p>Found by re-running the demo seeding script, which registers the same
     * address every time by design.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> register(@RequestBody RegisterRequest req) {
        UUID tenantId = UUID.randomUUID();
        try {
            admin.update("""
                    WITH new_tenant AS (
                        INSERT INTO tenant (id, name) VALUES (?, ?) RETURNING id
                    )
                    INSERT INTO app_user (id, tenant_id, email, password_hash, role)
                    SELECT ?, new_tenant.id, ?, ?, 'ADMIN' FROM new_tenant
                    """,
                    tenantId, req.tenantName(),
                    UUID.randomUUID(), req.email(), encoder.encode(req.password()));
        } catch (DuplicateKeyException e) {
            // 409, not 500. "That address is taken" is a fact about the
            // request, not a failure of the server, and a caller that cannot
            // tell the two apart cannot decide whether retrying is worth
            // anything.
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "An account already exists for that email address");
        }
        return Map.of("tenantId", tenantId.toString(), "email", req.email());
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        Map<String, Object> row;
        try {
            row = admin.queryForMap(
                    "SELECT id, tenant_id, email, password_hash, role FROM app_user WHERE email = ?",
                    req.email());
        } catch (EmptyResultDataAccessException e) {
            // Same response as a wrong password, deliberately. Distinguishing
            // them turns the login form into a way to discover which email
            // addresses have accounts.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        if (!encoder.matches(req.password(), (String) row.get("password_hash"))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        AuthenticatedUser user = new AuthenticatedUser(
                (UUID) row.get("id"), (UUID) row.get("tenant_id"),
                (String) row.get("email"), (String) row.get("role"));

        return new LoginResponse(jwt.issue(user), jwt.ttlSeconds(), user.email(), user.role());
    }

    /** Who the current token says you are. Useful for the client on reload. */
    @GetMapping("/me")
    public AuthenticatedUser me(@AuthenticationPrincipal AuthenticatedUser user) {
        return user;
    }
}
