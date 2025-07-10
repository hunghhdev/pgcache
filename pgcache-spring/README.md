# PgCache Spring Integration

Spring Framework integration for [PgCache](../pgcache-core/README.md), providing seamless Spring Cache abstraction support with automatic configuration.

## Features

- **Spring Cache Integration**: Full implementation of Spring's `Cache` and `CacheManager` interfaces
- **Auto-Configuration**: Automatic setup with Spring Boot's `@EnableAutoConfiguration`
- **Annotation Support**: Works with `@Cacheable`, `@CacheEvict`, `@CachePut`, and other Spring cache annotations
- **Configuration Properties**: YAML/Properties configuration support via `application.yml`
- **Health Monitoring**: Spring Boot Actuator integration for health checks and metrics
- **Multiple Cache Configuration**: Per-cache TTL and settings configuration
- **Background Cleanup**: Optional background cleanup of expired entries

## Quick Start

### 1. Add Dependencies

```xml
<dependency>
    <groupId>dev.hunghh</groupId>
    <artifactId>pgcache-spring</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 2. Enable PgCache

**Option A: Using @EnablePgCache annotation**
```java
@SpringBootApplication
@EnablePgCache
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

**Option B: Using Spring Boot Auto-Configuration**
```java
@SpringBootApplication
public class Application {
    // PgCache will be auto-configured if DataSource is available
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 3. Use Cache Annotations

```java
@Service
public class UserService {
    
    @Cacheable("users")
    public User getUser(Long id) {
        // This method will be cached
        return userRepository.findById(id);
    }
    
    @CacheEvict("users")
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }
    
    @CachePut(value = "users", key = "#user.id")
    public User updateUser(User user) {
        return userRepository.save(user);
    }
}
```

## Configuration

### Application Properties

```yaml
pgcache:
  enabled: true
  default-ttl: PT1H              # 1 hour TTL by default
  allow-null-values: true        # Allow caching null values
  table-name: pg_cache          # Default table name
  background-cleanup:
    enabled: true               # Enable background cleanup
    interval: PT30M             # Cleanup every 30 minutes
  
  # Per-cache configuration
  caches:
    users:
      ttl: PT2H                 # 2 hour TTL for users cache
      table-name: users_cache
    products:
      ttl: PT15M                # 15 minute TTL for products cache
      allow-null-values: false
    permanent-data:
      ttl: null                 # Permanent entries (no expiration)
```

**Application Properties Format:**
```properties
pgcache.enabled=true
pgcache.default-ttl=PT1H
pgcache.allow-null-values=true
pgcache.table-name=pg_cache
pgcache.background-cleanup.enabled=true
pgcache.background-cleanup.interval=PT30M

pgcache.caches.users.ttl=PT2H
pgcache.caches.users.table-name=users_cache
pgcache.caches.products.ttl=PT15M
pgcache.caches.products.allow-null-values=false
pgcache.caches.permanent-data.ttl=
```

### Programmatic Configuration

```java
@Configuration
@EnableCaching
public class CacheConfig {
    
    @Bean
    public PgCacheManager pgCacheManager(DataSource dataSource) {
        PgCacheManager.PgCacheConfiguration defaultConfig = 
            PgCacheManager.PgCacheConfiguration.builder()
                .defaultTtl(Duration.ofHours(1))
                .allowNullValues(true)
                .tableName("pg_cache")
                .backgroundCleanupEnabled(true)
                .backgroundCleanupInterval(Duration.ofMinutes(30))
                .build();
        
        PgCacheManager cacheManager = new PgCacheManager(dataSource, defaultConfig);
        
        // Configure specific caches
        PgCacheManager.PgCacheConfiguration usersCacheConfig = 
            PgCacheManager.PgCacheConfiguration.builder()
                .defaultTtl(Duration.ofHours(2))
                .tableName("users_cache")
                .build();
        
        cacheManager.setCacheConfiguration("users", usersCacheConfig);
        
        return cacheManager;
    }
}
```

## Advanced Usage

### Direct Cache API

```java
@Service
public class CacheService {
    
    @Autowired
    private CacheManager cacheManager;
    
    public void cacheOperations() {
        Cache cache = cacheManager.getCache("my-cache");
        
        // Basic operations
        cache.put("key", "value");
        String value = cache.get("key", String.class);
        cache.evict("key");
        
        // Conditional operations
        cache.putIfAbsent("key", "value");
        boolean evicted = cache.evictIfPresent("key");
        
        // Value loader (with automatic caching)
        String loaded = cache.get("key", () -> {
            // This will be called only if key is not in cache
            return loadExpensiveValue();
        });
        
        // PgCache specific operations
        if (cache instanceof PgCache) {
            PgCache pgCache = (PgCache) cache;
            long size = pgCache.size();
            pgCache.cleanupExpired();
        }
    }
}
```

### Cache Manager Operations

```java
@Service
public class CacheManagementService {
    
    @Autowired
    private PgCacheManager cacheManager;
    
    public void manageCaches() {
        // Get cache information
        Collection<String> cacheNames = cacheManager.getCacheNames();
        int cacheCount = cacheManager.getCacheCount();
        
        // Cache operations
        cacheManager.clearAll();
        cacheManager.cleanupExpiredAll();
        cacheManager.removeCache("old-cache");
        
        // Dynamic cache configuration
        PgCacheManager.PgCacheConfiguration config = 
            PgCacheManager.PgCacheConfiguration.builder()
                .defaultTtl(Duration.ofMinutes(30))
                .tableName("dynamic_cache")
                .build();
        
        cacheManager.setCacheConfiguration("dynamic-cache", config);
    }
}
```

## Health Monitoring

### Spring Boot Actuator

PgCache provides a health indicator for Spring Boot Actuator:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always
```

Health endpoint will show:
```json
{
  "status": "UP",
  "components": {
    "pgCache": {
      "status": "UP",
      "details": {
        "cache.count": 2,
        "cache.names": ["users", "products"],
        "cache.users.size": 150,
        "cache.products.size": 75,
        "cache.total.size": 225
      }
    }
  }
}
```

## TTL Configuration

### Duration Formats

TTL values can be specified in ISO-8601 duration format:

- `PT30S` - 30 seconds
- `PT5M` - 5 minutes
- `PT1H` - 1 hour
- `PT24H` - 24 hours
- `P1D` - 1 day
- `P1W` - 1 week
- `null` or empty - Permanent entries (no expiration)

### Per-Cache TTL

```yaml
pgcache:
  default-ttl: PT1H  # Global default: 1 hour
  caches:
    short-term:
      ttl: PT5M      # 5 minutes
    long-term:
      ttl: PT24H     # 24 hours
    permanent:
      ttl: null      # No expiration
```

## Thread Safety

PgCache Spring integration is fully thread-safe:

- All cache operations are thread-safe
- Cache creation is thread-safe
- Configuration changes are thread-safe
- Background cleanup is thread-safe

## Performance Considerations

1. **Connection Pooling**: Ensure proper DataSource configuration with connection pooling
2. **Background Cleanup**: Enable background cleanup for high-throughput applications
3. **Table Partitioning**: Consider PostgreSQL table partitioning for large datasets
4. **Monitoring**: Use Spring Boot Actuator to monitor cache performance

## Error Handling

- Cache operations gracefully handle database errors
- Failed cache operations are logged but don't break application flow
- Health indicator shows cache errors for monitoring
- Automatic retry logic for transient database issues

## Integration with Other Spring Features

### Spring Security

```java
@Service
public class SecurityService {
    
    @Cacheable(value = "user-permissions", key = "#username")
    public Set<String> getUserPermissions(String username) {
        // Expensive permission lookup
        return permissionRepository.findByUsername(username);
    }
}
```

### Spring Data JPA

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    @Cacheable("users")
    Optional<User> findById(Long id);
    
    @Cacheable(value = "users", key = "#username")
    Optional<User> findByUsername(String username);
}
```

## Migration from Other Cache Providers

PgCache implements the standard Spring Cache abstraction, making migration straightforward:

1. Replace cache provider dependency with pgcache-spring
2. Update configuration properties
3. Verify cache annotations still work
4. Test application functionality

No code changes are typically required for cache operations using Spring's cache annotations.
