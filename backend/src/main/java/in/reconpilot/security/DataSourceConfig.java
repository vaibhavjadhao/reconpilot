package in.reconpilot.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Two connections to the same database, with deliberately different privileges.
 *
 * <p><b>The application</b> connects as {@code reconpilot_app}: not the table
 * owner, not a superuser, so PostgreSQL's row-level security actually applies
 * to it. Its connections are wrapped by {@link TenantAwareDataSource}, which
 * stamps the current tenant onto each one.
 *
 * <p><b>Migrations and system tasks</b> connect as the owner. Flyway must alter
 * tables, and a startup sweep for batches interrupted by a restart must see
 * every tenant's rows -- neither is acting on behalf of a logged-in user, so
 * neither can have a tenant context.
 *
 * <p>Splitting them is the point. A single all-powerful connection is how "we
 * have row-level security" turns out to mean nothing, which is exactly what
 * defect D1 was.
 */
@Configuration
public class DataSourceConfig {

    // ------------------------------------------------- application runtime --

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties appDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * @param connectionDetails present when something else is supplying the
     *        connection -- most importantly Testcontainers via
     *        {@code @ServiceConnection}, which contributes this bean rather
     *        than setting {@code spring.datasource.*}.
     *
     *        <p>Honouring it is not optional. Replacing Spring Boot's
     *        auto-configured DataSource with a hand-built one silently
     *        disconnects {@code @ServiceConnection}, so the test suite goes on
     *        using whatever {@code application.properties} says -- which is the
     *        developer's own database. That is exactly how this suite once ran
     *        TRUNCATE against local development data.
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties appDataSourceProperties,
                                 ObjectProvider<JdbcConnectionDetails> connectionDetails) {
        var builder = appDataSourceProperties.initializeDataSourceBuilder();
        JdbcConnectionDetails details = connectionDetails.getIfAvailable();
        if (details != null) {
            builder.url(details.getJdbcUrl())
                   .username(details.getUsername())
                   .password(details.getPassword())
                   .driverClassName(details.getDriverClassName());
        }
        return new TenantAwareDataSource(builder.build());
    }

    // ----------------------------------------------- migrations and system --

    @Bean
    @ConfigurationProperties("app.datasource.admin")
    public DataSourceProperties adminDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("adminDataSource")
    public DataSource adminDataSource(
            @Qualifier("adminDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().build();
    }

    /**
     * The template everything ordinary uses, on the tenant-scoped connection.
     *
     * <p>Declared explicitly, and this is not optional. Spring Boot's
     * auto-configured JdbcTemplate is annotated
     * {@code @ConditionalOnMissingBean(JdbcOperations.class)}, so declaring
     * {@code adminJdbcTemplate} below caused it to back off entirely -- leaving
     * the admin template as the only candidate, and silently routing every
     * controller and service through the privileged connection.
     *
     * <p>The result was row-level security that worked perfectly at the
     * database level and applied to nothing, because no application query ever
     * reached it. Adding a bean removed another one, with no warning.
     */
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource tenantScoped) {
        return new JdbcTemplate(tenantScoped);
    }

    /** For work that legitimately spans tenants, and only that. */
    @Bean("adminJdbcTemplate")
    public JdbcTemplate adminJdbcTemplate(@Qualifier("adminDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
