package io.github.hunghhdev.pgcache.quarkus;

import io.github.hunghhdev.pgcache.core.NullValueMarker;
import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import io.quarkus.cache.Cache;
import io.smallrye.mutiny.Uni;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Quarkus Cache implementation backed by PgCacheStore.
 * Provides Quarkus Cache abstraction support with TTL capabilities.
 *
 * @since 1.5.0
 */
public class PgQuarkusCache implements Cache {

    private static final Logger logger = LoggerFactory.getLogger(PgQuarkusCache.class);

    private final String name;
    private final PgCacheStore cacheStore;
    private final Duration defaultTtl;
    private final boolean allowNullValues;
    private final TTLPolicy ttlPolicy;

    public PgQuarkusCache(String name, PgCacheStore cacheStore, Duration defaultTtl,
                          boolean allowNullValues, TTLPolicy ttlPolicy) {
        this.name = name;
        this.cacheStore = cacheStore;
        this.defaultTtl = defaultTtl;
        this.allowNullValues = allowNullValues;
        this.ttlPolicy = ttlPolicy != null ? ttlPolicy : TTLPolicy.ABSOLUTE;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getDefaultKey() {
        return "default";
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Uni<V> get(K key, Function<K, V> valueLoader) {
        return Uni.createFrom().item(() -> {
            String keyStr = toKeyString(key);

            // Try to get from cache first
            Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, true);
            if (optionalValue.isPresent()) {
                Object value = optionalValue.get();
                if (value instanceof NullValueMarker) {
                    return null;
                }
                return (V) value;
            }

            // Load value using the valueLoader
            V value = valueLoader.apply(key);

            // Store in cache
            if (value != null || allowNullValues) {
                if (defaultTtl != null) {
                    cacheStore.put(keyStr, value, defaultTtl, ttlPolicy);
                } else {
                    cacheStore.put(keyStr, value);
                }
            }

            return value;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Uni<V> getAsync(K key, Function<K, Uni<V>> valueLoader) {
        String keyStr = toKeyString(key);

        // Try to get from cache first
        Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, true);
        if (optionalValue.isPresent()) {
            Object value = optionalValue.get();
            if (value instanceof NullValueMarker) {
                return Uni.createFrom().nullItem();
            }
            return Uni.createFrom().item((V) value);
        }

        // Load value asynchronously using the valueLoader
        return valueLoader.apply(key)
            .onItem().invoke(value -> {
                if (value != null || allowNullValues) {
                    if (defaultTtl != null) {
                        cacheStore.put(keyStr, value, defaultTtl, ttlPolicy);
                    } else {
                        cacheStore.put(keyStr, value);
                    }
                }
            });
    }

    @Override
    public Uni<Void> invalidate(Object key) {
        return Uni.createFrom().item(() -> {
            if (key != null) {
                String keyStr = toKeyString(key);
                cacheStore.evict(keyStr);
                logger.debug("Invalidated key '{}' from cache '{}'", key, name);
            }
            return null;
        });
    }

    @Override
    public Uni<Void> invalidateAll() {
        return Uni.createFrom().item(() -> {
            cacheStore.clear();
            logger.debug("Invalidated all entries from cache '{}'", name);
            return null;
        });
    }

    @Override
    public Uni<Void> invalidateIf(Predicate<Object> predicate) {
        return Uni.createFrom().item(() -> {
            // For pattern-based eviction, we use evictByPattern with cache name prefix
            // Since we can't iterate keys easily, we use a pattern that matches all keys in this cache
            String pattern = name + ":%";
            cacheStore.evictByPattern(pattern);
            logger.debug("Invalidated entries by predicate from cache '{}'", name);
            return null;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Cache> T as(Class<T> type) {
        if (type.isAssignableFrom(this.getClass())) {
            return (T) this;
        }
        throw new IllegalArgumentException("Cannot cast " + this.getClass().getName() + " to " + type.getName());
    }

    /**
     * Get the underlying cache store.
     */
    public PgCacheStore getCacheStore() {
        return cacheStore;
    }

    /**
     * Get the cache size (number of non-expired entries).
     */
    public long size() {
        return cacheStore.size();
    }

    /**
     * Manually trigger cleanup of expired entries.
     */
    public void cleanupExpired() {
        cacheStore.cleanupExpired();
    }

    /**
     * Convert key to string representation for storage.
     */
    private String toKeyString(Object key) {
        return name + ":" + key.toString();
    }
}
