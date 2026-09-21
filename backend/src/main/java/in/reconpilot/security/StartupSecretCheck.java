package in.reconpilot.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start a deployed instance that is still using development secrets.
 *
 * <h2>Why this is a hard failure and not a warning</h2>
 *
 * The JWT signing secret is the whole of authentication. Anyone who knows it
 * can mint a token for any user of any tenant, and this one is committed to a
 * public repository in plain text. An instance running it is not "less secure",
 * it has no authentication at all.
 *
 * <p>A warning in a log nobody reads is not a control. Nothing about the system
 * would look wrong -- logins work, tokens verify, the dashboard loads -- which
 * is exactly why it has to refuse to start.
 *
 * <p>The check is gated on {@code RECONPILOT_REQUIRE_EXTERNAL_SECRETS}, which
 * the container image sets and a laptop does not. That way local development
 * stays frictionless and the check applies precisely where it matters: the
 * deployment that was assembled by a pipeline rather than by a person.
 */
@Component
public class StartupSecretCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSecretCheck.class);

    /** Kept verbatim so the comparison cannot drift from application.properties. */
    private static final String DEV_JWT_SECRET =
            "local-development-secret-change-me-0123456789abcdef";
    private static final List<String> DEV_DB_PASSWORDS = List.of("localdev", "localdev_app");

    private final boolean required;
    private final String jwtSecret;
    private final String appPassword;
    private final String ownerPassword;

    public StartupSecretCheck(
            @Value("${RECONPILOT_REQUIRE_EXTERNAL_SECRETS:false}") boolean required,
            @Value("${reconpilot.jwt.secret}") String jwtSecret,
            @Value("${spring.datasource.password}") String appPassword,
            @Value("${app.datasource.admin.password}") String ownerPassword) {
        this.required = required;
        this.jwtSecret = jwtSecret;
        this.appPassword = appPassword;
        this.ownerPassword = ownerPassword;
    }

    /**
     * Runs during context refresh, deliberately, rather than as an
     * {@code ApplicationRunner}.
     *
     * <p>An ApplicationRunner fires <b>after</b> the web server has started. The
     * first version of this class was one, and the logs showed
     * "Started BackendApplication" immediately before the refusal -- meaning
     * Tomcat had already accepted connections while the compromised secret was
     * live. Short, but a window that need not exist.
     *
     * <p>Failing here aborts the refresh, so the server never binds at all.
     */
    @PostConstruct
    void check() {
        if (!required) {
            log.info("Development mode: not checking for externalised secrets");
            return;
        }

        List<String> problems = new ArrayList<>();

        if (DEV_JWT_SECRET.equals(jwtSecret)) {
            problems.add("RECONPILOT_JWT_SECRET is still the development value, which is "
                       + "committed in plain text. Anyone holding it can mint a valid token "
                       + "for any user of any tenant.");
        }
        if (jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            problems.add("RECONPILOT_JWT_SECRET is shorter than 32 bytes; HS256 needs at "
                       + "least 256 bits of key material.");
        }
        if (DEV_DB_PASSWORDS.contains(appPassword)) {
            problems.add("DB_APP_PASSWORD is still a development default.");
        }
        if (DEV_DB_PASSWORDS.contains(ownerPassword)) {
            problems.add("DB_OWNER_PASSWORD is still a development default.");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: development secrets are in use.\n  - "
                  + String.join("\n  - ", problems)
                  + "\n\nSet these in the environment. If this really is a throwaway local "
                  + "instance, unset RECONPILOT_REQUIRE_EXTERNAL_SECRETS.");
        }

        log.info("Secret check passed: all credentials are externally supplied");
    }
}
