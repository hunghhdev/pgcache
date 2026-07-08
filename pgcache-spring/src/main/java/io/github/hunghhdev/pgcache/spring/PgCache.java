package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.CacheStatistics;
import io.github.hunghhdev.pgcache.core.NullValueMarker;
import io.github.hunghhdev.pgcache.core.PgCacheException;
import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.SqlPatterns;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring Cache implementation backed by PgCacheStore.
 * Provides full Spring Cache abstraction support with TTL capabilities.
 */
public class PgCache implements Cache {
    
    private static final Logger logger = LoggerFactory.getLogger(PgCache.class);
    
    private final String name;
    private final PgCacheStore cacheStore;
    private final Duration defaultTtl;
    private final boolean allowNullValues;
    private final TTLPolicy ttlPolicy;

    // Per-cache statistics: the store's counters are shared by every cache on
    // the same store, so meters tagged per cache must not read them
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong cachePuts = new AtomicLong();
    private final AtomicLong cacheEvictions = new AtomicLong();
    
    /**
     * Create a new PgCache instance.
     *
     * @param name the cache name
     * @param cacheStore the underlying cache store
     * @param defaultTtl default TTL for cache entries (null for permanent entries)
     * @param allowNullValues whether to allow null values
     * @param ttlPolicy the TTL policy (absolute or sliding)
     */
    public PgCache(String name, PgCacheStore cacheStore, Duration defaultTtl, boolean allowNullValues, TTLPolicy ttlPolicy) {
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
    public Object getNativeCache() {
        return cacheStore;
    }
    
    @Override
    public ValueWrapper get(Object key) {
        if (key == null) {
            return null;
        }
        try {
            String keyStr = toKeyString(key);
            Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, true);
            if (!optionalValue.isPresent()) {
                cacheMisses.incrementAndGet();
                return null;
            }
            cacheHits.incrementAndGet();
            Object value = optionalValue.get();
            if (value instanceof NullValueMarker) {
                return new SimpleValueWrapper(null);
            }
            return new SimpleValueWrapper(value);
        } catch (Exception e) {
            cacheMisses.incrementAndGet();
            logger.warn("Failed to get value from cache '{}' for key '{}': {}", name, key, e.getMessage());
            return null;
        }
    }
    
    @Override
    public <T> T get(Object key, Class<T> type) {
        if (key == null) {
            return null;
        }
        try {
            String keyStr = toKeyString(key);
            Optional<T> optionalValue = cacheStore.get(keyStr, type, true);
            if (!optionalValue.isPresent()) {
                cacheMisses.incrementAndGet();
                return null;
            }
            cacheHits.incrementAndGet();
            T value = optionalValue.get();
            if (value instanceof NullValueMarker) {
                return null;
            }
            return value;
        } catch (Exception e) {
            cacheMisses.incrementAndGet();
            logger.warn("Failed to get value from cache '{}' for key '{}' with type {}: {}",
                       name, key, safeTypeName(type), e.getMessage());
            return null;
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        if (key == null) {
            throw new IllegalArgumentException("Cache key cannot be null");
        }

        String keyStr = toKeyString(key);

        try {
            // Fast path for per-cache hit/miss accounting; a hit avoids the lock entirely
            // Always refresh TTL - let the core decide based on the entry's TTL policy
            Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, true);
            if (optionalValue.isPresent()) {
                cacheHits.incrementAndGet();
                Object value = optionalValue.get();
                // Check for null marker - indicates cached null value
                if (value instanceof NullValueMarker) {
                    return null;  // Return null for cached null value
                }
                return (T) value;
            }
            cacheMisses.incrementAndGet();

            // Single-flight load: the store's advisory lock guarantees only one
            // loader runs per key across threads and JVMs (Spring sync contract)
            return (T) cacheStore.getOrCompute(keyStr, Object.class, defaultTtl, ttlPolicy, () -> {
                try {
                    T loaded = valueLoader.call();
                    if (loaded != null || allowNullValues) {
                        cachePuts.incrementAndGet();
                    }
                    return loaded;
                } catch (Exception e) {
                    throw new LoaderFailure(e);
                }
            });
        } catch (LoaderFailure e) {
            throw new ValueRetrievalException(key, valueLoader, e.getCause());
        } catch (Exception e) {
            logger.error("Failed to get/load value from cache '{}' for key '{}': {}", name, key, e.getMessage());
            throw new PgCacheException("Cache operation failed for cache '" + name + "'", e);
        }
    }

    /** Carries a checked loader exception out of the Supplier-based single-flight path. */
    private static final class LoaderFailure extends RuntimeException {
        LoaderFailure(Throwable cause) {
            super(cause);
        }
    }
    
    @Override
    public void put(Object key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("Cache key cannot be null");
        }
        
        if (value == null && !allowNullValues) {
            return;
        }
        
        try {
            String keyStr = toKeyString(key);
            
            if (defaultTtl != null) {
                cacheStore.put(keyStr, value, defaultTtl, ttlPolicy);
            } else {
                cacheStore.put(keyStr, value);
            }
            cachePuts.incrementAndGet();

            logger.debug("Put value in cache '{}' for key '{}'", name, key);
        } catch (Exception e) {
            logger.error("Failed to put value in cache '{}' for key '{}': {}", name, key, e.getMessage());
            throw new PgCacheException("Cache put operation failed for cache '" + name + "'", e);
        }
    }
    
    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("Cache key cannot be null");
        }

        if (value == null && !allowNullValues) {
            return get(key);
        }

        try {
            String keyStr = toKeyString(key);

            // Atomic putIfAbsent - no race condition
            Optional<Object> existing;
            if (defaultTtl != null) {
                existing = cacheStore.putIfAbsent(keyStr, value, defaultTtl, ttlPolicy);
            } else {
                existing = cacheStore.putIfAbsent(keyStr, value);
            }

            if (existing.isPresent()) {
                // Key already existed - return the existing value
                Object existingValue = existing.get();
                if (existingValue instanceof NullValueMarker) {
                    return new SimpleValueWrapper(null);
                }
                return new SimpleValueWrapper(existingValue);
            }

            // Successfully inserted
            cachePuts.incrementAndGet();
            logger.debug("Put value in cache '{}' for key '{}' (if absent)", name, key);
            return null;

        } catch (Exception e) {
            logger.error("Failed to putIfAbsent value in cache '{}' for key '{}': {}", name, key, e.getMessage());
            throw new PgCacheException("Cache putIfAbsent operation failed for cache '" + name + "'", e);
        }
    }
    
    @Override
    public void evict(Object key) {
        if (key == null) {
            return;
        }
        try {
            String keyStr = toKeyString(key);
            cacheStore.evict(keyStr);
            cacheEvictions.incrementAndGet();
            logger.debug("Evicted key '{}' from cache '{}'", key, name);
        } catch (Exception e) {
            logger.error("Failed to evict key '{}' from cache '{}': {}", key, name, e.getMessage());
            throw new PgCacheException("Cache evict operation failed for cache '" + name + "'", e);
        }
    }
    
    @Override
    public boolean evictIfPresent(Object key) {
        if (key == null) {
            return false;
        }
        
        try {
            String keyStr = toKeyString(key);
            
            // Check if exists first
            Optional<Object> optionalExisting = cacheStore.get(keyStr, Object.class);
            if (!optionalExisting.isPresent()) {
                return false;
            }
            
            // Evict if present
            cacheStore.evict(keyStr);
            cacheEvictions.incrementAndGet();
            logger.debug("Evicted existing key '{}' from cache '{}'", key, name);
            return true;
            
        } catch (Exception e) {
            logger.warn("Failed to evictIfPresent key '{}' from cache '{}': {}", key, name, e.getMessage());
            return false;
        }
    }
    
    @Override
    public void clear() {
        try {
            cacheStore.evictByPattern(scopedLikePrefix() + "%");
            logger.debug("Cleared cache '{}'", name);
        } catch (Exception e) {
            logger.error("Failed to clear cache '{}': {}", name, e.getMessage());
            throw new PgCacheException("Cache clear operation failed for cache '" + name + "'", e);
        }
    }
    
    /**
     * Get the cache size (number of non-expired entries).
     */
    public long size() {
        try {
            return cacheStore.size(scopedLikePrefix() + "%");
        } catch (Exception e) {
            logger.warn("Failed to get size of cache '{}': {}", name, e.getMessage());
            return 0;
        }
    }
    
    /**
     * Manually trigger cleanup of expired entries in the underlying store.
     *
     * <p><strong>Scope:</strong> this affects ALL entries in the shared underlying store, not just
     * entries belonging to this cache instance.</p>
     *
     * @since 1.7.0
     */
    public void cleanupExpiredAllCaches() {
        try {
            cacheStore.cleanupExpired();
            logger.debug("Cleaned up expired entries (store-wide) triggered from cache '{}'", name);
        } catch (Exception e) {
            logger.warn("Failed to cleanup expired entries (store-wide) from cache '{}': {}", name, e.getMessage());
        }
    }

    /**
     * @deprecated since 1.7.0, use {@link #cleanupExpiredAllCaches()} -- name now matches actual scope.
     *     Removed in 2.0.0.
     */
    @Deprecated
    public void cleanupExpired() {
        cleanupExpiredAllCaches();
    }
    
    /**
     * Get value with option to refresh TTL (for sliding TTL).
     *
     * @param key the cache key
     * @param type the expected type of the value
     * @param refreshTTL whether to refresh TTL on access (for sliding TTL)
     * @return the cached value or null if not found
     */
    public <T> T get(Object key, Class<T> type, boolean refreshTTL) {
        if (key == null) {
            return null;
        }

        try {
            String keyStr = toKeyString(key);
            Optional<T> optionalValue = cacheStore.get(keyStr, type, refreshTTL);
            if (!optionalValue.isPresent()) {
                return null;
            }

            T value = optionalValue.get();
            if (value instanceof NullValueMarker) {
                return null;
            }

            return value;
        } catch (Exception e) {
            logger.warn("Failed to get value from cache '{}' for key '{}' with type {} and refreshTTL={}: {}",
                       name, key, safeTypeName(type), refreshTTL, e.getMessage());
            return null;
        }
    }

    private String safeTypeName(Class<?> type) {
        return type != null ? type.getSimpleName() : "null";
    }
    
    /**
     * Put value with explicit TTL policy.
     *
     * @param key the cache key
     * @param value the value to cache
     * @param ttl the TTL duration
     * @param ttlPolicy the TTL policy (absolute or sliding)
     */
    public void put(Object key, Object value, Duration ttl, TTLPolicy ttlPolicy) {
        if (key == null) {
            throw new IllegalArgumentException("Cache key cannot be null");
        }
        
        if (value == null && !allowNullValues) {
            return;
        }
        
        try {
            String keyStr = toKeyString(key);
            cacheStore.put(keyStr, value, ttl, ttlPolicy);
            cachePuts.incrementAndGet();
        } catch (Exception e) {
            logger.error("Failed to put value in cache '{}' for key '{}': {}", name, key, e.getMessage());
            throw new PgCacheException("Cache put operation failed for cache '" + name + "'", e);
        }
    }
    
    /**
     * Put value with explicit TTL (using default TTL policy).
     *
     * @param key the cache key
     * @param value the value to cache
     * @param ttl the TTL duration
     */
    public void put(Object key, Object value, Duration ttl) {
        put(key, value, ttl, ttlPolicy);
    }
    
    /**
     * Get remaining TTL for a cache entry.
     *
     * @param key the cache key
     * @return the remaining TTL or empty if not found or permanent
     */
    public Optional<Duration> getRemainingTTL(Object key) {
        if (key == null) {
            return Optional.empty();
        }
        
        try {
            String keyStr = toKeyString(key);
            return cacheStore.getRemainingTTL(keyStr);
        } catch (Exception e) {
            logger.warn("Failed to get remaining TTL for cache '{}' key '{}': {}", name, key, e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * Get TTL policy for a cache entry.
     *
     * @param key the cache key
     * @return the TTL policy or empty if not found
     */
    public Optional<TTLPolicy> getTTLPolicy(Object key) {
        if (key == null) {
            return Optional.empty();
        }
        
        try {
            String keyStr = toKeyString(key);
            return cacheStore.getTTLPolicy(keyStr);
        } catch (Exception e) {
            logger.warn("Failed to get TTL policy for cache '{}' key '{}': {}", name, key, e.getMessage());
            return Optional.empty();
        }
    }
    
    /**
     * Get the default TTL policy for this cache.
     *
     * @return the default TTL policy
     */
    public TTLPolicy getDefaultTTLPolicy() {
        return ttlPolicy;
    }
    
    /**
     * Refresh TTL for an existing cache entry.
     *
     * @param key the cache key
     * @param newTtl the new TTL duration
     * @return true if TTL was refreshed successfully, false if key not found
     */
    public boolean refreshTTL(Object key, Duration newTtl) {
        if (key == null) {
            return false;
        }

        try {
            String keyStr = toKeyString(key);
            return cacheStore.refreshTTL(keyStr, newTtl);
        } catch (Exception e) {
            logger.warn("Failed to refresh TTL for cache '{}' key '{}': {}", name, key, e.getMessage());
            return false;
        }
    }

    // ==================== Statistics (v1.3.0) ====================

    /**
     * Get cache statistics including hit/miss counts and rates.
     * Note: Statistics are shared across all caches using the same PgCacheStore.
     *
     * @return current cache statistics
     * @since 1.3.0
     */
    public CacheStatistics getStatistics() {
        return cacheStore.getStatistics();
    }

    /**
     * Statistics scoped to THIS cache only, unlike {@link #getStatistics()}
     * which reports the shared store-wide counters. Use these for anything
     * tagged per cache (metrics, health details).
     *
     * @return per-cache statistics
     * @since 1.8.0
     */
    public CacheStatistics getCacheStatistics() {
        return new CacheStatistics(cacheHits.get(), cacheMisses.get(), cachePuts.get(), cacheEvictions.get());
    }

    /**
     * Reset all cache statistics counters to zero — both the shared store-wide
     * counters and this cache's local counters.
     *
     * @since 1.3.0
     */
    public void resetStatistics() {
        cacheHits.set(0);
        cacheMisses.set(0);
        cachePuts.set(0);
        cacheEvictions.set(0);
        cacheStore.resetStatistics();
        logger.debug("Reset statistics for cache '{}'", name);
    }

    // ==================== Pattern Operations (v1.3.0) ====================

    /**
     * Evict all entries in this cache matching the given pattern.
     * The pattern is automatically prefixed with the cache name.
     * Uses SQL LIKE pattern matching (% for any characters, _ for single character).
     *
     * <p>Example: For cache named "users", calling evictByPattern("admin:%")
     * will evict all keys matching "users:admin:%"</p>
     *
     * @param pattern SQL LIKE pattern to match keys (will be prefixed with cache name)
     * @return number of entries evicted
     * @since 1.3.0
     */
    public int evictByPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return 0;
        }

        try {
            // Prefix pattern with cache name (escaped) for scoped eviction
            String scopedPattern = scopedLikePrefix() + pattern;
            int evicted = cacheStore.evictByPattern(scopedPattern);
            logger.debug("Evicted {} entries from cache '{}' matching pattern '{}'", evicted, name, pattern);
            return evicted;
        } catch (Exception e) {
            logger.warn("Failed to evict by pattern '{}' from cache '{}': {}", pattern, name, e.getMessage());
            return 0;
        }
    }

    /**
     * Convert key to string representation for storage.
     */
    private String toKeyString(Object key) {
        return name + ":" + key.toString();
    }

    /**
     * Returns the cache-scoped prefix with SQL LIKE meta-characters in the cache name escaped.
     * Use to build patterns like {@code scopedLikePrefix() + "%"} for store-wide queries.
     */
    private String scopedLikePrefix() {
        return SqlPatterns.escapeLikePattern(name) + ":";
    }
    
    /**
     * Simple ValueWrapper implementation.
     */
    private static class SimpleValueWrapper implements ValueWrapper {
        private final Object value;
        
        public SimpleValueWrapper(Object value) {
            this.value = value;
        }
        
        @Override
        public Object get() {
            return value;
        }
    }
}
