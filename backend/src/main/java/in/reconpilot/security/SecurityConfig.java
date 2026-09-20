package in.reconpilot.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    /**
     * BCrypt, not SHA-256 or MD5.
     *
     * <p>Fast hashes are the wrong tool for passwords precisely because they
     * are fast: a GPU tries billions of guesses a second against them. BCrypt
     * is deliberately slow and its cost is tunable upward as hardware improves,
     * and it salts each password so two users with the same password get
     * different hashes -- which defeats precomputed rainbow tables.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // No cookies are used, so there is no ambient credential for a
            // third-party site to trigger a request with -- which is the attack
            // CSRF tokens exist to stop. A bearer token must be attached
            // deliberately by our own client.
            .csrf(csrf -> csrf.disable())

            // Nothing is remembered between requests; the token carries it all.
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/").permitAll()
                // Everything else requires a valid token. Default-deny: a new
                // endpoint is protected because nobody remembered to open it,
                // rather than exposed because nobody remembered to close it.
                .anyRequest().authenticated())

            // Spring Security's default entry point answers 403 for a caller
            // with no credentials at all. That is the wrong distinction:
            //   401 -- we do not know who you are
            //   403 -- we know who you are, and you may not do this
            // A client cannot tell "log in again" from "you lack permission"
            // if both are 403.
            .exceptionHandling(e -> e.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
