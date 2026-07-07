package io.github.hunghhdev.pgcache.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
// CompositeMeterRegistryAutoConfiguration referenced by name: actuator-autoconfigure
// is optional, and a class literal risks NoClassDefFoundError during annotation
// parsing when it is absent from the classpath
@AutoConfigureAfter(
        value = PgCacheAutoConfiguration.class,
        name = "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
public class PgCacheMetricsAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(PgCacheMetricsAutoConfiguration.class);

    @Bean
    public MeterBinder pgCacheMetricsBinder(PgCacheManager cacheManager) {
        // bindTo may be invoked once per registry of a composite — track seen
        // registries and register the cache-created listener exactly once
        java.util.Set<io.micrometer.core.instrument.MeterRegistry> registries =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicBoolean listenerRegistered =
                new java.util.concurrent.atomic.AtomicBoolean();

        return registry -> {
            if (!registries.add(registry)) {
                return;
            }
            for (String cacheName : cacheManager.getCacheNames()) {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache instanceof PgCache) {
                    new PgCacheMetrics((PgCache) cache).bindTo(registry);
                    logger.debug("Bound metrics for cache '{}'", cacheName);
                }
            }
            if (listenerRegistered.compareAndSet(false, true)) {
                cacheManager.addCacheCreatedListener(cache -> registries.forEach(r -> {
                    new PgCacheMetrics(cache).bindTo(r);
                    logger.debug("Bound metrics for newly created cache '{}'", cache.getName());
                }));
            }
            logger.info("PgCache metrics enabled for {} caches", cacheManager.getCacheNames().size());
        };
    }
}
