package in.reconpilot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads the bearer token, establishes who the caller is, and -- critically --
 * sets the tenant for the rest of the request.
 *
 * <p>The tenant comes from the <b>signed token</b>, never from a header or
 * query parameter the caller controls. A {@code ?tenantId=} parameter would let
 * anyone read anyone's data by editing a URL, which is among the most common
 * multi-tenant vulnerabilities there is.
 *
 * <p>The finally block is not tidiness. Request threads are pooled and reused,
 * so a tenant left in the ThreadLocal becomes the <em>next</em> request's
 * tenant -- a cross-tenant leak arising from thread reuse rather than from any
 * mistake in the business logic.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    public JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                AuthenticatedUser user = jwt.verify(header.substring(7));
                if (user != null) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    TenantContext.set(user.tenantId());
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
