package dev.hunghh.pgcache.spring;

import dev.hunghh.pgcache.core.PgCacheStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Spring CacheManager implementation that manages PgCache instances.
 * Supports dynamic cache creation and configuration per cache name.
 */
public class PgCacheManager implements CacheManager {
    
    private static final Logger logger = LoggerFactory.getLogger(PgCacheManager.class);
    
    private final DataSource dataSource;
    private final PgCacheConfiguration defaultConfiguration;
    private final ConcurrentMap<String, Cache> cacheMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PgCacheConfiguration> cacheConfigurations = new ConcurrentHashMap<>();
    
    /**
     * Create a new PgCacheManager with default configuration.
     *
     * @param dataSource the DataSource for database connections
     * @param defaultConfiguration default configuration for caches
     */
    public PgCacheManager(DataSource dataSource, PgCacheConfiguration defaultConfiguration) {
        this.dataSource = dataSource;
        this.defaultConfiguration = defaultConfiguration;
    }
    
    @Override
    public Cache getCache(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Cache name cannot be null");
        }
        
        return cacheMap.computeIfAbsent(name, this::createCache);
    }
    
    @Override
    public Collection<String> getCacheNames() {
        return cacheMap.keySet();
    }
    
    /**
     * Set configuration for a specific cache name.
     *
     * @param cacheName the cache name
     * @param configuration the configuration to use
     */
    public void setCacheConfiguration(String cacheName, PgCacheConfiguration configuration) {
        if (cacheName == null) {
            throw new IllegalArgumentException("Cache name cannot be null");
        }
        if (configuration == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        
        cacheConfigurations.put(cacheName, configuration);
        
        // If cache already exists, recreate it with new configuration
        Cache existingCache = cacheMap.remove(cacheName);
        if (existingCache != null) {
            logger.info("Recreating cache '{}' with new configuration", cacheName);
        }
    }
    
    /**
     * Remove a cache by name.
     *
     * @param cacheName the cache name to remove
     * @return true if cache was removed, false if it didn't exist
     */
    public boolean removeCache(String cacheName) {
        if (cacheName == null) {
            return false;
        }
        
        Cache removed = cacheMap.remove(cacheName);
        cacheConfigurations.remove(cacheName);
        
        if (removed != null) {
            // Clear the cache before removal
            try {
                removed.clear();
                logger.info("Removed and cleared cache '{}'", cacheName);
                return true;
            } catch (Exception e) {
                logger.warn("Failed to clear cache '{}' during removal: {}", cacheName, e.getMessage());
            }
        }
        
        return false;
    }
    
    /**
     * Clear all caches managed by this manager.
     */
    public void clearAll() {
        logger.info("Clearing all {} caches", cacheMap.size());
        
        for (Cache cache : cacheMap.values()) {
            try {
                cache.clear();
            } catch (Exception e) {
                logger.warn("Failed to clear cache '{}': {}", cache.getName(), e.getMessage());
            }
        }
    }
    
    /**
     * Get the total number of caches managed.
     */
    public int getCacheCount() {
        return cacheMap.size();
    }
    
    /**
     * Cleanup expired entries in all caches.
     */
    public void cleanupExpiredAll() {
        logger.debug("Cleaning up expired entries in all {} caches", cacheMap.size());
        
        for (Cache cache : cacheMap.values()) {
            if (cache instanceof PgCache) {
                try {
                    ((PgCache) cache).cleanupExpired();
                } catch (Exception e) {
                    logger.warn("Failed to cleanup expired entries in cache '{}': {}", 
                               cache.getName(), e.getMessage());
                }
            }
        }
    }
    
    /**
     * Create a new cache instance with the appropriate configuration.
     */
    private Cache createCache(String name) {
        PgCacheConfiguration config = cacheConfigurations.getOrDefault(name, defaultConfiguration);
        
        logger.info("Creating new cache '{}' with TTL: {}, Allow null values: {}, Background cleanup: {}", 
                   name, config.getDefaultTtl(), config.isAllowNullValues(), config.isBackgroundCleanupEnabled());
        
        try {
            // Create the underlying cache store
            PgCacheStore.Builder builder = PgCacheStore.builder()
                    .dataSource(dataSource)
                    .autoCreateTable(true);
            
            // Configure background cleanup if enabled
            if (config.isBackgroundCleanupEnabled() && config.getBackgroundCleanupInterval() != null) {
                builder.enableBackgroundCleanup(true)
                       .cleanupIntervalMinutes(config.getBackgroundCleanupInterval().toMinutes());
            }
            
            PgCacheStore cacheStore = builder.build();
            
            // Create the Spring Cache wrapper
            return new PgCache(name, cacheStore, config.getDefaultTtl(), config.isAllowNullValues());
            
        } catch (Exception e) {
            logger.error("Failed to create cache '{}': {}", name, e.getMessage());
            throw new RuntimeException("Failed to create cache: " + name, e);
        }
    }
    
    /**
     * Configuration class for individual caches.
     */
    public static class PgCacheConfiguration {
        private final Duration defaultTtl;
        private final boolean allowNullValues;
        private final String tableName;
        private final boolean backgroundCleanupEnabled;
        private final Duration backgroundCleanupInterval;
        
        public PgCacheConfiguration(Duration defaultTtl, boolean allowNullValues, String tableName,
                                  boolean backgroundCleanupEnabled, Duration backgroundCleanupInterval) {
            this.defaultTtl = defaultTtl;
            this.allowNullValues = allowNullValues;
            this.tableName = tableName != null ? tableName : "pg_cache";
            this.backgroundCleanupEnabled = backgroundCleanupEnabled;
            this.backgroundCleanupInterval = backgroundCleanupInterval;
        }
        
        public Duration getDefaultTtl() {
            return defaultTtl;
        }
        
        public boolean isAllowNullValues() {
            return allowNullValues;
        }
        
        public String getTableName() {
            return tableName;
        }
        
        public boolean isBackgroundCleanupEnabled() {
            return backgroundCleanupEnabled;
        }
        
        public Duration getBackgroundCleanupInterval() {
            return backgroundCleanupInterval;
        }
        
        /**
         * Create a builder for PgCacheConfiguration.
         */
        public static Builder builder() {
            return new Builder();
        }
        
        /**
         * Builder for PgCacheConfiguration.
         */
        public static class Builder {
            private Duration defaultTtl = Duration.ofHours(1); // 1 hour default
            private boolean allowNullValues = true;
            private String tableName = "pg_cache";
            private boolean backgroundCleanupEnabled = false;
            private Duration backgroundCleanupInterval = Duration.ofMinutes(30);
            
            public Builder defaultTtl(Duration defaultTtl) {
                this.defaultTtl = defaultTtl;
                return this;
            }
            
            public Builder allowNullValues(boolean allowNullValues) {
                this.allowNullValues = allowNullValues;
                return this;
            }
            
            public Builder tableName(String tableName) {
                this.tableName = tableName;
                return this;
            }
            
            public Builder backgroundCleanupEnabled(boolean enabled) {
                this.backgroundCleanupEnabled = enabled;
                return this;
            }
            
            public Builder backgroundCleanupInterval(Duration interval) {
                this.backgroundCleanupInterval = interval;
                return this;
            }
            
            public PgCacheConfiguration build() {
                return new PgCacheConfiguration(defaultTtl, allowNullValues, tableName,
                                               backgroundCleanupEnabled, backgroundCleanupInterval);
            }
        }
    }
}
