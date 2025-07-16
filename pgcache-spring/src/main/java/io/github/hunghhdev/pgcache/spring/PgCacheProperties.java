package io.github.hunghhdev.pgcache.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for PgCache Spring integration.
 * Supports YAML/Properties configuration via application.yml or application.properties.
 */
@ConfigurationProperties(prefix = "pgcache")
public class PgCacheProperties {
    
    /**
     * Whether PgCache is enabled.
     */
    private boolean enabled = true;
    
    /**
     * Default TTL for cache entries. If null, entries are permanent.
     */
    private Duration defaultTtl = Duration.ofHours(1);
    
    /**
     * Whether to allow null values in cache.
     */
    private boolean allowNullValues = true;
    
    /**
     * Default table name for cache storage.
     */
    private String tableName = "pg_cache";
    
    /**
     * Background cleanup configuration.
     */
    private BackgroundCleanup backgroundCleanup = new BackgroundCleanup();
    
    /**
     * Per-cache configurations.
     */
    private Map<String, CacheConfig> caches = new HashMap<>();
    
    // Getters and setters
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public Duration getDefaultTtl() {
        return defaultTtl;
    }
    
    public void setDefaultTtl(Duration defaultTtl) {
        this.defaultTtl = defaultTtl;
    }
    
    public boolean isAllowNullValues() {
        return allowNullValues;
    }
    
    public void setAllowNullValues(boolean allowNullValues) {
        this.allowNullValues = allowNullValues;
    }
    
    public String getTableName() {
        return tableName;
    }
    
    public void setTableName(String tableName) {
        this.tableName = tableName;
    }
    
    public BackgroundCleanup getBackgroundCleanup() {
        return backgroundCleanup;
    }
    
    public void setBackgroundCleanup(BackgroundCleanup backgroundCleanup) {
        this.backgroundCleanup = backgroundCleanup;
    }
    
    public Map<String, CacheConfig> getCaches() {
        return caches;
    }
    
    public void setCaches(Map<String, CacheConfig> caches) {
        this.caches = caches;
    }
    
    /**
     * Background cleanup configuration.
     */
    public static class BackgroundCleanup {
        
        /**
         * Whether background cleanup is enabled.
         */
        private boolean enabled = false;
        
        /**
         * Interval between background cleanup runs.
         */
        private Duration interval = Duration.ofMinutes(30);
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public Duration getInterval() {
            return interval;
        }
        
        public void setInterval(Duration interval) {
            this.interval = interval;
        }
    }
    
    /**
     * Per-cache configuration.
     */
    public static class CacheConfig {
        
        /**
         * TTL for this specific cache. If null, uses global default.
         */
        private Duration ttl;
        
        /**
         * Whether to allow null values for this cache. If null, uses global default.
         */
        private Boolean allowNullValues;
        
        /**
         * Table name for this specific cache. If null, uses global default.
         */
        private String tableName;
        
        /**
         * Background cleanup configuration for this cache.
         */
        private BackgroundCleanup backgroundCleanup;
        
        public Duration getTtl() {
            return ttl;
        }
        
        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
        
        public Boolean getAllowNullValues() {
            return allowNullValues;
        }
        
        public void setAllowNullValues(Boolean allowNullValues) {
            this.allowNullValues = allowNullValues;
        }
        
        public String getTableName() {
            return tableName;
        }
        
        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
        
        public BackgroundCleanup getBackgroundCleanup() {
            return backgroundCleanup;
        }
        
        public void setBackgroundCleanup(BackgroundCleanup backgroundCleanup) {
            this.backgroundCleanup = backgroundCleanup;
        }
        
        /**
         * Merge this cache config with global defaults.
         */
        public PgCacheManager.PgCacheConfiguration toConfiguration(PgCacheProperties globalProperties) {
            Duration effectiveTtl = this.ttl != null ? this.ttl : globalProperties.getDefaultTtl();
            boolean effectiveAllowNullValues = this.allowNullValues != null ? 
                this.allowNullValues : globalProperties.isAllowNullValues();
            String effectiveTableName = this.tableName != null ? 
                this.tableName : globalProperties.getTableName();
            
            BackgroundCleanup effectiveCleanup = this.backgroundCleanup != null ? 
                this.backgroundCleanup : globalProperties.getBackgroundCleanup();
            
            return new PgCacheManager.PgCacheConfiguration(
                effectiveTtl,
                effectiveAllowNullValues,
                effectiveTableName,
                effectiveCleanup.isEnabled(),
                effectiveCleanup.getInterval(),
                io.github.hunghhdev.pgcache.core.TTLPolicy.ABSOLUTE
            );
        }
    }
    
    /**
     * Convert to default cache configuration.
     */
    public PgCacheManager.PgCacheConfiguration toDefaultConfiguration() {
        return new PgCacheManager.PgCacheConfiguration(
            defaultTtl,
            allowNullValues,
            tableName,
            backgroundCleanup.isEnabled(),
            backgroundCleanup.getInterval(),
            io.github.hunghhdev.pgcache.core.TTLPolicy.ABSOLUTE
        );
    }
}
