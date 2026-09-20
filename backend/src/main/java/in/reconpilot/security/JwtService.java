package in.reconpilot.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Issues and verifies JSON Web Tokens.
 *
 * <h2>What a JWT actually is</h2>
 *
 * Three base64 segments: a header, a payload of claims, and a signature over
 * the first two. The payload is <b>encoded, not encrypted</b> -- anyone holding
 * the token can read it. What the signature guarantees is that nobody has
 * <em>changed</em> it without the secret.
 *
 * <p>So a token may carry an id and a role, and must never carry a secret.
 *
 * <h2>Why tokens rather than sessions</h2>
 *
 * A session id means the server must remember every logged-in user, which
 * becomes shared state that every instance needs. A signed token carries its
 * own proof, so any instance can verify it with no lookup and no shared store.
 *
 * <p>The cost is that a token cannot be un-issued. Until it expires it stays
 * valid, even if the user is disabled -- which is why expiry is short and why
 * anything needing instant revocation needs a denylist on top.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${reconpilot.jwt.secret}") String secret,
                      @Value("${reconpilot.jwt.ttl-minutes:120}") long ttlMinutes) {
        // HS256 requires at least 256 bits of key material; a short secret
        // makes the signature cheap to forge, so jjwt refuses one outright.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public String issue(AuthenticatedUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.userId().toString())
                .claim("tenantId", user.tenantId().toString())
                .claim("email", user.email())
                .claim("role", user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @return the caller, or null if the token is absent, altered or expired. */
    public AuthenticatedUser verify(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            return new AuthenticatedUser(
                    UUID.fromString(c.getSubject()),
                    UUID.fromString(c.get("tenantId", String.class)),
                    c.get("email", String.class),
                    c.get("role", String.class));
        } catch (Exception e) {
            // Expired, tampered with, or simply not a token. All the same to a
            // caller: not authenticated. Distinguishing them in the response
            // would tell an attacker which guess was closer.
            return null;
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
