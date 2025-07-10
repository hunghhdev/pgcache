package dev.hunghh.pgcache.spring;

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
            
            // Test cache operations
            long totalSize = 0;
            for (String cacheName : cacheManager.getCacheNames()) {
                try {
                    PgCache cache = (PgCache) cacheManager.getCache(cacheName);
                    if (cache != null) {
                        long cacheSize = cache.size();
                        totalSize += cacheSize;
                        details.put("cache." + cacheName + ".size", cacheSize);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to get size for cache '{}': {}", cacheName, e.getMessage());
                    details.put("cache." + cacheName + ".error", e.getMessage());
                }
            }
            
            details.put("cache.total.size", totalSize);
            
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
