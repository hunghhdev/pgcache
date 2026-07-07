package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import io.quarkus.arc.DefaultBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import javax.sql.DataSource;
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

        // The store is shared by every cache, so it must accept null markers
        // when the global default OR any per-cache override allows null values.
        // Per-cache enforcement happens in PgQuarkusCache.
        boolean anyCacheAllowsNulls = config.allowNullValues() ||
            config.caches().values().stream()
                .anyMatch(cacheConfig -> cacheConfig.allowNullValues().orElse(false));

        PgCacheStore.Builder builder = PgCacheStore.builder()
            .dataSource(dataSource)
            .allowNullValues(anyCacheAllowsNulls);

        // Configure background cleanup
        if (config.backgroundCleanup().enabled()) {
            builder.enableBackgroundCleanup(true)
                   .cleanupInterval(config.backgroundCleanup().interval());
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
                TTLPolicy parsed = TTLPolicy.parse(policy).orElseGet(() -> {
                    logger.warn("Invalid TTL policy '{}' for cache '{}', using default", policy, name);
                    return TTLPolicy.ABSOLUTE;
                });
                cc.setTtlPolicy(parsed);
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

    void dispose(@Disposes PgCacheStore cacheStore) {
        logger.info("Disposing PgCacheStore");
        cacheStore.close();
        logger.info("PgCacheStore disposed");
    }
}
