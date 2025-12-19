package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import io.quarkus.arc.DefaultBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * CDI producer for PgCache components in Quarkus.
 *
 * @since 1.5.0
 */
@ApplicationScoped
public class PgQuarkusCacheProducer {

    private static final Logger logger = LoggerFactory.getLogger(PgQuarkusCacheProducer.class);

    @Inject
    DataSource dataSource;

    @Inject
    PgQuarkusCacheConfig config;

    @Produces
    @ApplicationScoped
    @DefaultBean
    public PgCacheStore pgCacheStore() {
        logger.info("Creating PgCacheStore for Quarkus");

        PgCacheStore.Builder builder = PgCacheStore.builder()
            .dataSource(dataSource)
            .allowNullValues(config.allowNullValues());

        // Configure background cleanup
        if (config.backgroundCleanup().enabled()) {
            Duration interval = config.backgroundCleanup().interval();
            builder.enableBackgroundCleanup(true)
                   .cleanupIntervalMinutes(interval.toMinutes());
        }

        return builder.build();
    }

    @Produces
    @ApplicationScoped
    @DefaultBean
    public PgQuarkusCacheManager pgQuarkusCacheManager(PgCacheStore cacheStore) {
        logger.info("Creating PgQuarkusCacheManager for Quarkus");

        // Build cache configs from configuration
        Map<String, PgQuarkusCacheManager.CacheConfig> cacheConfigs = new HashMap<>();
        config.caches().forEach((name, cacheConfig) -> {
            PgQuarkusCacheManager.CacheConfig cc = new PgQuarkusCacheManager.CacheConfig();
            cacheConfig.ttl().ifPresent(cc::setTtl);
            cacheConfig.ttlPolicy().ifPresent(policy -> {
                try {
                    cc.setTtlPolicy(TTLPolicy.valueOf(policy.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid TTL policy '{}' for cache '{}', using default", policy, name);
                }
            });
            cacheConfig.allowNullValues().ifPresent(cc::setAllowNullValues);
            cacheConfigs.put(name, cc);
        });

        return new PgQuarkusCacheManager(
            cacheStore,
            config.defaultTtl().orElse(null),
            config.allowNullValues(),
            config.parseTtlPolicy(),
            cacheConfigs
        );
    }
}
