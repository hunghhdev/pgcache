package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.CacheEventListener;
import io.github.hunghhdev.pgcache.core.PgCacheStore;
import io.github.hunghhdev.pgcache.core.TTLPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Spring CacheManager implementation that manages PgCache instances.
 * Supports dynamic cache creation and configuration per cache name.
 */
public class PgCacheManager implements CacheManager, DisposableBean {
    
    private static final Logger logger = LoggerFactory.getLogger(PgCacheManager.class);
    
    private final DataSource dataSource;
    private final PgCacheConfiguration defaultConfiguration;
    private final ConcurrentMap<String, Cache> cacheMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PgCacheConfiguration> cacheConfigurations = new ConcurrentHashMap<>();
    private final ConcurrentMap<StoreConfigurationKey, PgCacheStore> storeMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<StoreConfigurationKey, Integer> storeUsageCount = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, StoreConfigurationKey> cacheToStoreKey = new ConcurrentHashMap<>();
    private final List<CacheEventListener> eventListeners;
    private final List<Consumer<PgCache>> cacheCreatedListeners = new CopyOnWriteArrayList<>();
    
    /**
     * Create a new PgCacheManager with default configuration.
     *
     * @param dataSource the DataSource for database connections
     * @param defaultConfiguration default configuration for caches
     */
    public PgCacheManager(DataSource dataSource, PgCacheConfiguration defaultConfiguration) {
        this(dataSource, defaultConfiguration, Collections.emptyList());
    }

    /**
     * Create a new PgCacheManager with default configuration and listeners.
     *
     * @param dataSource the DataSource for database connections
     * @param defaultConfiguration default configuration for caches
     * @param eventListeners list of cache event listeners
     */
    public PgCacheManager(DataSource dataSource, PgCacheConfiguration defaultConfiguration, List<CacheEventListener> eventListeners) {
        this.dataSource = dataSource;
        this.defaultConfiguration = defaultConfiguration;
        this.eventListeners = eventListeners != null ? eventListeners : Collections.emptyList();
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

        StoreConfigurationKey storeKey = cacheToStoreKey.remove(cacheName);
        if (storeKey != null) {
            storeUsageCount.computeIfPresent(storeKey, (k, count) -> {
                int newCount = count - 1;
                if (newCount <= 0) {
                    PgCacheStore store = storeMap.remove(k);
                    if (store != null) {
                        store.close();
                        logger.debug("Closed and removed store for key: {}", k);
                    }
                    return null;
                }
                return newCount;
            });
        }

        if (removed != null) {
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

    @Override
    public void destroy() {
        logger.info("Shutting down PgCacheManager");
        storeMap.values().forEach(PgCacheStore::close);
        logger.info("PgCacheManager shutdown completed");
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

    public void addCacheCreatedListener(Consumer<PgCache> listener) {
        if (listener != null) {
            cacheCreatedListeners.add(listener);
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
            StoreConfigurationKey storeKey = StoreConfigurationKey.from(config);
            PgCacheStore cacheStore = storeMap.computeIfAbsent(
                    storeKey,
                    key -> createCacheStore(config)
            );

            storeUsageCount.merge(storeKey, 1, Integer::sum);
            cacheToStoreKey.put(name, storeKey);

            PgCache cache = new PgCache(name, cacheStore, config.getDefaultTtl(), config.isAllowNullValues(), config.getTtlPolicy());
            cacheCreatedListeners.forEach(listener -> listener.accept(cache));
            return cache;

        } catch (Exception e) {
            logger.error("Failed to create cache '{}': {}", name, e.getMessage());
            throw new RuntimeException("Failed to create cache: " + name, e);
        }
    }

    private PgCacheStore createCacheStore(PgCacheConfiguration config) {
        PgCacheStore.Builder builder = PgCacheStore.builder()
                .dataSource(dataSource)
                .autoCreateTable(true)
                .tableName(config.getTableName())
                .allowNullValues(config.isAllowNullValues());

        if (config.isBackgroundCleanupEnabled() && config.getBackgroundCleanupInterval() != null) {
            builder.enableBackgroundCleanup(true)
                   .cleanupIntervalMinutes(config.getBackgroundCleanupInterval().toMinutes());
        }

        eventListeners.forEach(builder::addEventListener);
        return builder.build();
    }

    private static final class StoreConfigurationKey {
        private final boolean allowNullValues;
        private final String tableName;
        private final boolean backgroundCleanupEnabled;
        private final Duration backgroundCleanupInterval;

        private StoreConfigurationKey(boolean allowNullValues, String tableName,
                                      boolean backgroundCleanupEnabled, Duration backgroundCleanupInterval) {
            this.allowNullValues = allowNullValues;
            this.tableName = tableName;
            this.backgroundCleanupEnabled = backgroundCleanupEnabled;
            this.backgroundCleanupInterval = backgroundCleanupInterval;
        }

        static StoreConfigurationKey from(PgCacheConfiguration configuration) {
            return new StoreConfigurationKey(
                    configuration.isAllowNullValues(),
                    configuration.getTableName(),
                    configuration.isBackgroundCleanupEnabled(),
                    configuration.getBackgroundCleanupInterval()
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof StoreConfigurationKey)) {
                return false;
            }
            StoreConfigurationKey that = (StoreConfigurationKey) o;
            return allowNullValues == that.allowNullValues
                    && backgroundCleanupEnabled == that.backgroundCleanupEnabled
                    && Objects.equals(tableName, that.tableName)
                    && Objects.equals(backgroundCleanupInterval, that.backgroundCleanupInterval);
        }

        @Override
        public int hashCode() {
            return Objects.hash(allowNullValues, tableName, backgroundCleanupEnabled, backgroundCleanupInterval);
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
        private final TTLPolicy ttlPolicy;
        
        public PgCacheConfiguration(Duration defaultTtl, boolean allowNullValues, String tableName,
                                  boolean backgroundCleanupEnabled, Duration backgroundCleanupInterval,
                                  TTLPolicy ttlPolicy) {
            this.defaultTtl = defaultTtl;
            this.allowNullValues = allowNullValues;
            this.tableName = tableName != null ? tableName : "pgcache_store";
            this.backgroundCleanupEnabled = backgroundCleanupEnabled;
            this.backgroundCleanupInterval = backgroundCleanupInterval;
            this.ttlPolicy = ttlPolicy != null ? ttlPolicy : TTLPolicy.ABSOLUTE;
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
        
        public TTLPolicy getTtlPolicy() {
            return ttlPolicy;
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
            private String tableName = "pgcache_store";
            private boolean backgroundCleanupEnabled = false;
            private Duration backgroundCleanupInterval = Duration.ofMinutes(30);
            private TTLPolicy ttlPolicy = TTLPolicy.ABSOLUTE;
            
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
            
            public Builder ttlPolicy(TTLPolicy ttlPolicy) {
                this.ttlPolicy = ttlPolicy;
                return this;
            }
            
            public PgCacheConfiguration build() {
                return new PgCacheConfiguration(defaultTtl, allowNullValues, tableName,
                                               backgroundCleanupEnabled, backgroundCleanupInterval, ttlPolicy);
            }
        }
    }
}
