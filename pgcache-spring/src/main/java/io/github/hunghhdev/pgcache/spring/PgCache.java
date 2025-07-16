package io.github.hunghhdev.pgcache.spring;

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
            // For sliding TTL, we need to refresh the TTL on access
            boolean refreshTtl = ttlPolicy == TTLPolicy.SLIDING;
            Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, refreshTtl);
            
            if (!optionalValue.isPresent()) {
                return null;
            }
            
            return new SimpleValueWrapper(optionalValue.get());
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
            // For sliding TTL, we need to refresh the TTL on access
            boolean refreshTtl = ttlPolicy == TTLPolicy.SLIDING;
            Optional<T> optionalValue = cacheStore.get(keyStr, type, refreshTtl);
            return optionalValue.orElse(null);
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
            // For sliding TTL, we need to refresh the TTL on access
            boolean refreshTtl = ttlPolicy == TTLPolicy.SLIDING;
            Optional<Object> optionalValue = cacheStore.get(keyStr, Object.class, refreshTtl);
            if (optionalValue.isPresent()) {
                return (T) optionalValue.get();
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
            
            // Check if value already exists
            Optional<Object> optionalExisting = cacheStore.get(keyStr, Object.class);
            if (optionalExisting.isPresent()) {
                return new SimpleValueWrapper(optionalExisting.get());
            }
            
            // Put the new value
            if (defaultTtl != null) {
                cacheStore.put(keyStr, value, defaultTtl);
            } else {
                cacheStore.put(keyStr, value);
            }
            
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
            Optional<T> optionalValue = cacheStore.get(keyStr, type, refreshTTL);
            return optionalValue.orElse(null);
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
