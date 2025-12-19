package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.TTLPolicy;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Configuration for PgCache Quarkus integration.
 *
 * <p>Example configuration in application.properties:</p>
 * <pre>
 * pgcache.default-ttl=PT1H
 * pgcache.allow-null-values=true
 * pgcache.ttl-policy=ABSOLUTE
 *
 * # Per-cache configuration
 * pgcache.caches.users.ttl=PT2H
 * pgcache.caches.users.ttl-policy=SLIDING
 * </pre>
 *
 * @since 1.5.0
 */
@ConfigMapping(prefix = "pgcache")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface PgQuarkusCacheConfig {

    /**
     * Default TTL for cache entries.
     * If not specified, entries are permanent.
     */
    Optional<Duration> defaultTtl();

    /**
     * Whether to allow null values in the cache.
     */
    @WithDefault("true")
    boolean allowNullValues();

    /**
     * Default TTL policy for cache entries.
     * ABSOLUTE: entry expires at creation_time + TTL
     * SLIDING: entry expires at last_access_time + TTL
     */
    @WithDefault("ABSOLUTE")
    String ttlPolicy();

    /**
     * Background cleanup configuration.
     */
    BackgroundCleanupConfig backgroundCleanup();

    /**
     * Per-cache configuration.
     */
    @WithName("caches")
    Map<String, CacheInstanceConfig> caches();

    interface BackgroundCleanupConfig {
        /**
         * Whether background cleanup is enabled.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Interval between cleanup runs.
         */
        @WithDefault("PT30M")
        Duration interval();
    }

    interface CacheInstanceConfig {
        /**
         * TTL for this specific cache.
         */
        Optional<Duration> ttl();

        /**
         * TTL policy for this specific cache.
         */
        Optional<String> ttlPolicy();

        /**
         * Whether to allow null values for this specific cache.
         */
        Optional<Boolean> allowNullValues();
    }

    /**
     * Parse TTL policy from string.
     */
    default TTLPolicy parseTtlPolicy() {
        try {
            return TTLPolicy.valueOf(ttlPolicy().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TTLPolicy.ABSOLUTE;
        }
    }
}
