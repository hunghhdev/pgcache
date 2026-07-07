package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.CacheStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health indicator for PgCache.
 * Provides health information for Spring Boot Actuator.
 * Registered automatically by {@link PgCacheHealthAutoConfiguration}.
 */
public class PgCacheHealthIndicator implements HealthIndicator {
    
    private static final Logger logger = LoggerFactory.getLogger(PgCacheHealthIndicator.class);
    
    private final PgCacheManager cacheManager;
    
    public PgCacheHealthIndicator(PgCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }
    
    @Override
    public Health health() {
        try {
            Health.Builder builder = Health.up();
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("cache.count", cacheManager.getCacheCount());
            details.put("cache.names", cacheManager.getCacheNames());

            long totalSize = 0;
            for (String cacheName : cacheManager.getCacheNames()) {
                try {
                    org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
                    if (cache instanceof PgCache) {
                        long cacheSize = ((PgCache) cache).size();
                        totalSize += cacheSize;
                        details.put("cache." + cacheName + ".size", cacheSize);
                    } else if (cache != null) {
                        details.put("cache." + cacheName + ".type", cache.getClass().getSimpleName());
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get info for cache '{}': {}", cacheName, e.getMessage());
                    details.put("cache." + cacheName + ".error", e.getMessage());
                }
            }
            details.put("cache.total.size", totalSize);

            // Per-store statistics (each store is shared by all caches with matching config)
            cacheManager.getStoreStatistics().forEach((storeKey, stats) -> {
                String prefix = "stats." + storeKey + ".";
                details.put(prefix + "hits", stats.getHitCount());
                details.put(prefix + "misses", stats.getMissCount());
                details.put(prefix + "hitRate", String.format("%.2f%%", stats.getHitRate() * 100));
                details.put(prefix + "puts", stats.getPutCount());
                details.put(prefix + "evictions", stats.getEvictionCount());
            });

            return builder.withDetails(details).build();

        } catch (Exception e) {
            logger.error("PgCache health check failed: {}", e.getMessage());
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("cache.count", cacheManager.getCacheCount())
                    .build();
        }
    }
}
