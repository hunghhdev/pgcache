# PgCache

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.6.0-green.svg)](https://central.sonatype.com/artifact/io.github.hunghhdev/pgcache)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**A simple caching library that uses your existing PostgreSQL as cache backend.**

Perfect for small-to-medium applications that want caching without the complexity and cost of dedicated cache infrastructure.

## Why PgCache?

**You already have PostgreSQL. Why add Redis?**

| Scenario | Traditional Approach | With PgCache |
|----------|---------------------|--------------|
| Small/Medium app | PostgreSQL + Redis | PostgreSQL only |
| Infrastructure | 2 systems to maintain | 1 system |
| Monthly cost | -200+ for Redis | /usr/bin/zsh extra |
| Complexity | Connection pools, failover for both | Single database |

### Best For

- Startups and small teams wanting to keep infrastructure simple
- Applications with moderate caching needs (< 100k cache entries)
- Projects where PostgreSQL is already the primary database
- When you need caching but Redis/Memcached is overkill

### Not Ideal For

- High-throughput systems needing millions of ops/sec
- Sub-millisecond latency requirements
- Systems already using Redis with complex data structures
- Very large cache datasets (> 1M entries)

## Features

- **Zero extra infrastructure** - Uses your existing PostgreSQL
- **Spring Boot integration** - Works with `@Cacheable`, `@CacheEvict`, auto-configuration
- **Quarkus integration** - Works with `@CacheResult`, MicroProfile Health
- **Async API** - Non-blocking operations (`getAsync`, `putAsync`)
- **Event Listeners** - Monitor cache events (put, evict, clear)
- **Sliding TTL** - Active entries stay cached longer (like Redis)
- **Null value caching** - Properly cache null results
- **Batch operations** - `getAll`, `putAll`, `evictAll` for efficiency
- **Cache statistics** - Hit/miss counts, hit rate monitoring
- **Micrometer metrics** - Auto-configured metrics for monitoring
- **Pattern eviction** - Evict keys by pattern (e.g., `user:%`)
- **JSONB storage** - Efficient storage with PostgreSQL's native JSON
- **UNLOGGED tables** - Optimized for cache performance
- **Background cleanup** - Automatic expired entry removal

## Quick Start

### Maven

```xml
<!-- Core library (standalone usage) -->
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-core</artifactId>
  <version>1.6.0</version>
</dependency>

<!-- Spring Boot integration -->
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-spring</artifactId>
  <version>1.6.0</version>
</dependency>

<!-- Quarkus integration -->
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-quarkus</artifactId>
  <version>1.6.0</version>
</dependency>
```

### Spring Boot Usage

```java
@SpringBootApplication
@EnablePgCache
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

@Service
public class UserService {

    @Cacheable("users")
    public User getUser(Long id) {
        return userRepository.findById(id);
    }

    @CacheEvict("users")
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }
}
```

### Configuration

```yaml
# application.yml
pgcache:
  default-ttl: PT1H                    # 1 hour default TTL
  allow-null-values: true
  background-cleanup:
    enabled: true
    interval: PT30M                    # Cleanup every 30 minutes

  caches:
    users:
      ttl: PT2H
      ttl-policy: SLIDING              # Reset TTL on access
    products:
      ttl: PT6H
      ttl-policy: ABSOLUTE             # Fixed expiration
```

### Quarkus Usage

```java
@ApplicationScoped
public class UserService {

    @Inject
    PgQuarkusCacheManager cacheManager;

    public Uni<User> getUser(Long id) {
        PgQuarkusCache cache = cacheManager.getOrCreateCache("users");
        // Uses async API under the hood
        return cache.getAsync("user:" + id, key -> userRepository.findById(id));
    }
}
```

**Quarkus Configuration:**

```properties
# application.properties
pgcache.default-ttl=PT1H
pgcache.allow-null-values=true
pgcache.ttl-policy=ABSOLUTE
pgcache.background-cleanup.enabled=true
pgcache.background-cleanup.interval=PT30M

# Per-cache settings
pgcache.caches.users.ttl=PT2H
pgcache.caches.users.ttl-policy=SLIDING
```

### Standalone Usage (Core)

```java
PgCacheClient cache = PgCacheStore.builder()
    .dataSource(dataSource)
    .build();

// Store with TTL
cache.put("user:123", user, Duration.ofHours(1));

// Retrieve
Optional<User> user = cache.get("user:123", User.class);

// Async Operations (v1.6.0)
cache.getAsync("user:123", User.class)
     .thenAccept(opt -> System.out.println(opt.orElse(null)));

// Key Check (v1.6.0) - Efficient existence check
boolean exists = cache.containsKey("user:123");

// Pattern Operations
cache.getKeys("user:%"); // Get all user keys
cache.evictByPattern("user:%"); // Evict all user keys
```

### Event Listeners (v1.6.0)

Monitor cache operations by registering listeners:

```java
PgCacheStore.builder()
    .dataSource(dataSource)
    .addEventListener(new CacheEventListener() {
        @Override
        public void onPut(String key, Object value) {
            log.info("Cached: {}", key);
        }
        @Override
        public void onEvict(String key) {
            log.info("Evicted: {}", key);
        }
    })
    .build();
```

In **Spring Boot**, simply define a bean implementing `CacheEventListener`:

```java
@Component
public class MyCacheListener implements CacheEventListener {
    // ... overrides
}
```

### Quarkus Health Check (v1.6.0)

Add cache health status to your Quarkus Health endpoint:

```java
@Liveness
@ApplicationScoped
public class CacheHealthCheck implements HealthCheck {
    @Inject PgQuarkusHealthCheck pgCacheHealth;

    @Override
    public HealthCheckResponse call() {
        return pgCacheHealth.check().isUp() 
             ? HealthCheckResponse.up("pgcache") 
             : HealthCheckResponse.down("pgcache");
    }
}
```

## Advanced Features

### Batch Operations (v1.3.0)

```java
// Get multiple values at once
Map<String, User> users = cache.getAll(
    Arrays.asList("user:1", "user:2", "user:3"),
    User.class
);

// Put multiple values
Map<String, User> entries = Map.of(
    "user:1", user1,
    "user:2", user2
);
cache.putAll(entries, Duration.ofHours(1));
```

### Cache Statistics

```java
CacheStatistics stats = cache.getStatistics();
System.out.println("Hit Rate: " + stats.getHitRate());
```

### Micrometer Metrics

When using `pgcache-spring` with Micrometer, metrics are auto-configured:
- `pgcache.gets` (counter)
- `pgcache.puts` (counter)
- `pgcache.evictions` (counter)
- `pgcache.size` (gauge)
- `pgcache.hit.rate` (gauge)

## Sliding vs Absolute TTL

```java
// Absolute TTL: expires at creation_time + TTL
cache.put("key", value, Duration.ofHours(1));

// Sliding TTL: expires at last_access_time + TTL
cache.put("key", value, Duration.ofHours(1), TTLPolicy.SLIDING);
```

## Performance

PgCache uses **UNLOGGED tables** (no WAL overhead) and **JSONB** storage for performance.
- **Read**: ~1-5ms
- **Write**: ~2-10ms
- **Throughput**: Hundreds to low thousands ops/sec

## Migration

### From 1.5.x to 1.6.0

No database changes required.

**New Features:**
- **Async API**: `getAsync`, `putAsync` in `PgCacheClient`
- **Key Operations**: `containsKey`, `getKeys(pattern)`
- **Event Listeners**: `CacheEventListener` interface
- **Quarkus**: `PgQuarkusCache` now uses non-blocking async internal API
- **Spring**: Auto-detection of `CacheEventListener` beans

### From 1.4.x to 1.5.x

No database changes required. Added Quarkus integration.

### From 1.3.x to 1.4.0

No database changes required. Added Micrometer metrics.

### From 1.2.x to 1.3.0

No database changes required. Added Batch operations and Statistics.

### From 1.0.x/1.1.x to 1.2.x

Schema update required for Sliding TTL:

```sql
ALTER TABLE pgcache_store
  ADD COLUMN IF NOT EXISTS ttl_policy VARCHAR(10) DEFAULT 'ABSOLUTE',
  ADD COLUMN IF NOT EXISTS last_accessed TIMESTAMP DEFAULT now();

CREATE INDEX IF NOT EXISTS pgcache_store_sliding_ttl_idx
  ON pgcache_store (ttl_policy, last_accessed)
  WHERE ttl_policy = 'SLIDING';
```

## License

MIT License - see [LICENSE](LICENSE) for details.
