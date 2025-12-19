package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quarkus CacheManager implementation backed by PgCacheStore.
 * Manages multiple PgQuarkusCache instances sharing the same underlying database.
 *
 * @since 1.5.0
 */
public class PgQuarkusCacheManager implements CacheManager {

    private static final Logger logger = LoggerFactory.getLogger(PgQuarkusCacheManager.class);

    private final PgCacheStore cacheStore;
    private final Map<String, PgQuarkusCache> caches = new ConcurrentHashMap<>();
    private final Duration defaultTtl;
    private final boolean allowNullValues;
    private final TTLPolicy defaultTtlPolicy;
    private final Map<String, CacheConfig> cacheConfigs;

    public PgQuarkusCacheManager(PgCacheStore cacheStore, Duration defaultTtl,
                                  boolean allowNullValues, TTLPolicy defaultTtlPolicy) {
        this(cacheStore, defaultTtl, allowNullValues, defaultTtlPolicy, Collections.emptyMap());
    }

    public PgQuarkusCacheManager(PgCacheStore cacheStore, Duration defaultTtl,
                                  boolean allowNullValues, TTLPolicy defaultTtlPolicy,
                                  Map<String, CacheConfig> cacheConfigs) {
        this.cacheStore = cacheStore;
        this.defaultTtl = defaultTtl;
        this.allowNullValues = allowNullValues;
        this.defaultTtlPolicy = defaultTtlPolicy != null ? defaultTtlPolicy : TTLPolicy.ABSOLUTE;
        this.cacheConfigs = cacheConfigs != null ? cacheConfigs : Collections.emptyMap();

        logger.info("PgQuarkusCacheManager initialized with defaultTtl={}, allowNullValues={}, defaultTtlPolicy={}",
                   defaultTtl, allowNullValues, this.defaultTtlPolicy);
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableCollection(caches.keySet());
    }

    @Override
    public Optional<Cache> getCache(String name) {
        PgQuarkusCache cache = caches.computeIfAbsent(name, this::createCache);
        return Optional.of(cache);
    }

    /**
     * Create a new cache with the given name.
     */
    private PgQuarkusCache createCache(String name) {
        CacheConfig config = cacheConfigs.get(name);

        Duration ttl = config != null && config.getTtl() != null ? config.getTtl() : defaultTtl;
        TTLPolicy policy = config != null && config.getTtlPolicy() != null ? config.getTtlPolicy() : defaultTtlPolicy;
        boolean allowNull = config != null ? config.isAllowNullValues() : allowNullValues;

        logger.debug("Creating cache '{}' with ttl={}, ttlPolicy={}, allowNullValues={}",
                    name, ttl, policy, allowNull);

        return new PgQuarkusCache(name, cacheStore, ttl, allowNull, policy);
    }

    /**
     * Get cache with fallback creation.
     */
    public PgQuarkusCache getOrCreateCache(String name) {
        return caches.computeIfAbsent(name, this::createCache);
    }

    /**
     * Get the underlying cache store.
     */
    public PgCacheStore getCacheStore() {
        return cacheStore;
    }

    /**
     * Configuration for individual caches.
     */
    public static class CacheConfig {
        private Duration ttl;
        private TTLPolicy ttlPolicy;
        private boolean allowNullValues = true;

        public CacheConfig() {}

        public CacheConfig(Duration ttl, TTLPolicy ttlPolicy, boolean allowNullValues) {
            this.ttl = ttl;
            this.ttlPolicy = ttlPolicy;
            this.allowNullValues = allowNullValues;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public TTLPolicy getTtlPolicy() {
            return ttlPolicy;
        }

        public void setTtlPolicy(TTLPolicy ttlPolicy) {
            this.ttlPolicy = ttlPolicy;
        }

        public boolean isAllowNullValues() {
            return allowNullValues;
        }

        public void setAllowNullValues(boolean allowNullValues) {
            this.allowNullValues = allowNullValues;
        }
    }
}
