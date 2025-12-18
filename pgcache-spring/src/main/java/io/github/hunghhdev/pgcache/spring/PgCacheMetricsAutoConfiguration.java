package io.github.hunghhdev.pgcache.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.Cache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for PgCache Micrometer metrics.
 * Automatically binds cache metrics when Micrometer is on the classpath.
 *
 * @since 1.4.0
 */
@Configuration
@ConditionalOnClass({MeterRegistry.class, PgCacheMetrics.class})
@ConditionalOnBean(PgCacheManager.class)
@AutoConfigureAfter({PgCacheAutoConfiguration.class, CompositeMeterRegistryAutoConfiguration.class})
public class PgCacheMetricsAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(PgCacheMetricsAutoConfiguration.class);

    @Bean
    public MeterBinder pgCacheMetricsBinder(PgCacheManager cacheManager) {
        return registry -> {
            for (String cacheName : cacheManager.getCacheNames()) {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache instanceof PgCache) {
                    new PgCacheMetrics((PgCache) cache).bindTo(registry);
                    logger.debug("Bound metrics for cache '{}'", cacheName);
                }
            }
            logger.info("PgCache metrics enabled for {} caches", cacheManager.getCacheNames().size());
        };
    }
}
