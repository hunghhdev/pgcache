package io.github.hunghhdev.pgcache.spring;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration that exposes {@link PgCacheHealthIndicator} to Spring Boot
 * Actuator when actuator is on the classpath and a {@link PgCacheManager} was
 * auto-configured.
 *
 * @since 1.7.1
 */
@Configuration
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(PgCacheManager.class)
@AutoConfigureAfter(PgCacheAutoConfiguration.class)
public class PgCacheHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PgCacheHealthIndicator pgCacheHealthIndicator(PgCacheManager cacheManager) {
        return new PgCacheHealthIndicator(cacheManager);
    }
}
