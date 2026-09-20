package in.reconpilot;

import in.reconpilot.mdr.MdrCalculator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers domain objects as Spring beans.
 *
 * <p>The domain classes themselves carry no Spring annotations, so they can be
 * unit tested in milliseconds without starting a context. The framework knows
 * about the domain; the domain does not know about the framework.
 */
@Configuration
public class DomainConfig {

    @Bean
    MdrCalculator mdrCalculator() {
        return new MdrCalculator();
    }
}
