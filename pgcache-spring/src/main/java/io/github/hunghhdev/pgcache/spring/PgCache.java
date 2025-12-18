package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.CacheStatistics;
import io.github.hunghhdev.pgcache.core.NullValueMarker;
import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;

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
            // Always refresh TTL - let the core decide based on the entry's TTL policy
            Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, true);

            if (!optionalValue.isPresent()) {
                return null;
            }

            Object value = optionalValue.get();
            // Check for null marker - indicates cached null value
            if (value instanceof NullValueMarker) {
                return new SimpleValueWrapper(null);
            }

            return new SimpleValueWrapper(value);
        } catch (Exception e) {
            logger.warn("Failed to get value from cache '{}' for key '{}': {}", name, key, e.getMessage());
            return null;
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        if (key == null) {
            return null;
        }

        try {
            String keyStr = toKeyString(key);
            // Always refresh TTL - let the core decide based on the entry's TTL policy
            Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, true);
            if (!optionalValue.isPresent()) {
                return null;
            }

            Object value = optionalValue.get();
            // Check for null marker - indicates cached null value
            if (value instanceof NullValueMarker) {
                return null;
            }

            // Re-fetch with proper type if not null marker
            return cacheStore.get(keyStr, type, false).orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to get value from cache '{}' for key '{}' with type {}: {}",
                       name, key, type.getSimpleName(), e.getMessage());
            return null;
        }
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        if (key == null) {
            throw new IllegalArgumentException("Cache key cannot be null");
        }

        try {
            String keyStr = toKeyString(key);

            // Try to get from cache first
            // Always refresh TTL - let the core decide based on the entry's TTL policy
            Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, true);
            if (optionalValue.isPresent()) {
                Object value = optionalValue.get();
                // Check for null marker - indicates cached null value
                if (value instanceof NullValueMarker) {
                    return null;  // Return null for cached null value
                }
                return (T) value;
            }

            // Load value using the valueLoader
            try {
                T value = valueLoader.call();

                // Store in cache
                if (value != null || allowNullValues) {
                    if (defaultTtl != null) {
                        cacheStore.put(keyStr, value, defaultTtl, ttlPolicy);
                    } else {
                        cacheStore.put(keyStr, value);
                    }
                }

                return value;
            } catch (Exception e) {
                throw new ValueRetrievalException(key, valueLoader, e);
            }

        } catch (Exception e) {
            logger.error("Failed to get/load value from cache '{}' for key '{}': {}", name, key, e.getMessage());
            throw new RuntimeException("Cache operation failed", e);
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
            
            logger.debug("Put value in cache '{}' for key '{}'", name, key);
        } catch (Exception e) {
            logger.error("Failed to put value in cache '{}' for key '{}': {}", name, key, e.getMessage());
            throw new RuntimeException("Cache put operation failed", e);
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
            logger.debug("Put value in cache '{}' for key '{}' (if absent)", name, key);
            return null;

        } catch (Exception e) {
            logger.error("Failed to putIfAbsent value in cache '{}' for key '{}': {}", name, key, e.getMessage());
            throw new RuntimeException("Cache putIfAbsent operation failed", e);
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
            logger.debug("Evicted key '{}' from cache '{}'", key, name);
        } catch (Exception e) {
            logger.warn("Failed to evict key '{}' from cache '{}': {}", key, name, e.getMessage());
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
            cacheStore.clear();
            logger.debug("Cleared cache '{}'", name);
        } catch (Exception e) {
            logger.error("Failed to clear cache '{}': {}", name, e.getMessage());
            throw new RuntimeException("Cache clear operation failed", e);
        }
    }
    
    /**
     * Get the cache size (number of non-expired entries).
     */
    public long size() {
        try {
            return cacheStore.size();
        } catch (Exception e) {
            logger.warn("Failed to get size of cache '{}': {}", name, e.getMessage());
            return 0;
        }
    }
    
    /**
     * Manually trigger cleanup of expired entries.
     */
    public void cleanupExpired() {
        try {
            cacheStore.cleanupExpired();
            logger.debug("Cleaned up expired entries in cache '{}'", name);
        } catch (Exception e) {
            logger.warn("Failed to cleanup expired entries in cache '{}': {}", name, e.getMessage());
        }
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
            Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, refreshTTL);
            if (!optionalValue.isPresent()) {
                return null;
            }

            Object value = optionalValue.get();
            // Check for null marker - indicates cached null value
            if (value instanceof NullValueMarker) {
                return null;
            }

            // Re-fetch with proper type if not null marker
            return cacheStore.get(keyStr, type, false).orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to get value from cache '{}' for key '{}' with type {} and refreshTTL={}: {}",
                       name, key, type.getSimpleName(), refreshTTL, e.getMessage());
            return null;
        }
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
        } catch (Exception e) {
            logger.warn("Failed to put value into cache '{}' for key '{}': {}", name, key, e.getMessage());
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
     * Reset all cache statistics counters to zero.
     *
     * @since 1.3.0
     */
    public void resetStatistics() {
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
            // Prefix pattern with cache name for scoped eviction
            String scopedPattern = name + ":" + pattern;
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
