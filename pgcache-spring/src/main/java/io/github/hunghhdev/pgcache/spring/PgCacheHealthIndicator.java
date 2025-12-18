package io.github.hunghhdev.pgcache.spring;

import io.github.hunghhdev.pgcache.core.CacheStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health indicator for PgCache.
 * Provides health information for Spring Boot Actuator.
 */
@Component
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
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
            
            // Test cache operations and gather statistics
            long totalSize = 0;
            CacheStatistics aggregatedStats = null;

            for (String cacheName : cacheManager.getCacheNames()) {
                try {
                    PgCache cache = (PgCache) cacheManager.getCache(cacheName);
                    if (cache != null) {
                        long cacheSize = cache.size();
                        totalSize += cacheSize;
                        details.put("cache." + cacheName + ".size", cacheSize);

                        // Get statistics from first cache (shared across all)
                        if (aggregatedStats == null) {
                            aggregatedStats = cache.getStatistics();
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get info for cache '{}': {}", cacheName, e.getMessage());
                    details.put("cache." + cacheName + ".error", e.getMessage());
                }
            }

            details.put("cache.total.size", totalSize);

            // Add statistics if available
            if (aggregatedStats != null) {
                details.put("stats.hits", aggregatedStats.getHitCount());
                details.put("stats.misses", aggregatedStats.getMissCount());
                details.put("stats.hitRate", String.format("%.2f%%", aggregatedStats.getHitRate() * 100));
                details.put("stats.puts", aggregatedStats.getPutCount());
                details.put("stats.evictions", aggregatedStats.getEvictionCount());
            }
            
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
