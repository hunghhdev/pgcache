package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.TTLPolicy;

import java.time.Duration;

/**
 * Per-cache configuration passed from Spring Boot properties / programmatic setup
 * to {@link PgCacheManager} when constructing caches.
 *
 * <p>This class replaces {@code PgCacheManager.PgCacheConfiguration} (which remains as a
 * deprecated subclass for backward compatibility, removal in 2.0.0).</p>
 *
 * @since 1.7.0
 */
public class CacheStoreConfig {

    private static final String DEFAULT_TABLE_NAME = "pg_cache";

    private final Duration defaultTtl;
    private final boolean allowNullValues;
    private final String tableName;
    private final boolean backgroundCleanupEnabled;
    private final Duration backgroundCleanupInterval;
    private final TTLPolicy ttlPolicy;

    public CacheStoreConfig(Duration defaultTtl, boolean allowNullValues, String tableName,
                            boolean backgroundCleanupEnabled, Duration backgroundCleanupInterval,
                            TTLPolicy ttlPolicy) {
        this.defaultTtl = defaultTtl;
        this.allowNullValues = allowNullValues;
        this.tableName = tableName != null ? tableName : DEFAULT_TABLE_NAME;
        this.backgroundCleanupEnabled = backgroundCleanupEnabled;
        this.backgroundCleanupInterval = backgroundCleanupInterval;
        this.ttlPolicy = ttlPolicy != null ? ttlPolicy : TTLPolicy.ABSOLUTE;
    }

    public Duration getDefaultTtl() { return defaultTtl; }
    public boolean isAllowNullValues() { return allowNullValues; }
    public String getTableName() { return tableName; }
    public boolean isBackgroundCleanupEnabled() { return backgroundCleanupEnabled; }
    public Duration getBackgroundCleanupInterval() { return backgroundCleanupInterval; }
    public TTLPolicy getTtlPolicy() { return ttlPolicy; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Duration defaultTtl = Duration.ofHours(1);
        private boolean allowNullValues = true;
        private String tableName = DEFAULT_TABLE_NAME;
        private boolean backgroundCleanupEnabled = false;
        private Duration backgroundCleanupInterval = Duration.ofMinutes(30);
        private TTLPolicy ttlPolicy = TTLPolicy.ABSOLUTE;

        public Builder defaultTtl(Duration v) { this.defaultTtl = v; return this; }
        public Builder allowNullValues(boolean v) { this.allowNullValues = v; return this; }
        public Builder tableName(String v) { this.tableName = v; return this; }
        public Builder backgroundCleanupEnabled(boolean v) { this.backgroundCleanupEnabled = v; return this; }
        public Builder backgroundCleanupInterval(Duration v) { this.backgroundCleanupInterval = v; return this; }
        public Builder ttlPolicy(TTLPolicy v) { this.ttlPolicy = v; return this; }

        public CacheStoreConfig build() {
            return new CacheStoreConfig(defaultTtl, allowNullValues, tableName,
                                       backgroundCleanupEnabled, backgroundCleanupInterval, ttlPolicy);
        }
    }
}
