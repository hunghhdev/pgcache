# PgCache

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.4.0-green.svg)](https://central.sonatype.com/artifact/io.github.hunghhdev/pgcache)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**A simple caching library that uses your existing PostgreSQL as cache backend.**

Perfect for small-to-medium applications that want caching without the complexity and cost of dedicated cache infrastructure.

## Why PgCache?

**You already have PostgreSQL. Why add Redis?**

| Scenario | Traditional Approach | With PgCache |
|----------|---------------------|--------------|
| Small/Medium app | PostgreSQL + Redis | PostgreSQL only |
| Infrastructure | 2 systems to maintain | 1 system |
| Monthly cost | $50-200+ for Redis | $0 extra |
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
- **Spring Boot integration** - Works with `@Cacheable`, `@CacheEvict`, etc.
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
  <version>1.4.0</version>
</dependency>

<!-- Spring Boot integration -->
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-spring</artifactId>
  <version>1.4.0</version>
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

### Standalone Usage (without Spring)

```java
PgCacheClient cache = PgCacheStore.builder()
    .dataSource(dataSource)
    .build();

// Store with TTL
cache.put("user:123", user, Duration.ofHours(1));

// Store with sliding TTL (resets on access)
cache.put("session:abc", session, Duration.ofMinutes(30), TTLPolicy.SLIDING);

// Retrieve
Optional<User> user = cache.get("user:123", User.class);

// Evict
cache.evict("user:123");
```

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
    "user:2", user2,
    "user:3", user3
);
cache.putAll(entries, Duration.ofHours(1));

// Evict multiple keys
cache.evictAll(Arrays.asList("user:1", "user:2"));

// Evict by pattern - remove all keys starting with "user:"
cache.evictByPattern("user:%");
```

### Cache Statistics (v1.3.0)

```java
CacheStatistics stats = cache.getStatistics();

System.out.println("Hits: " + stats.getHitCount());
System.out.println("Misses: " + stats.getMissCount());
System.out.println("Hit Rate: " + stats.getHitRate());  // 0.0 - 1.0
System.out.println("Puts: " + stats.getPutCount());
System.out.println("Evictions: " + stats.getEvictionCount());

// Reset statistics
cache.resetStatistics();
```

### Micrometer Metrics (v1.4.0)

When using `pgcache-spring` with Micrometer on the classpath, metrics are auto-configured:

```yaml
# application.yml - metrics available at /actuator/metrics
management:
  endpoints:
    web:
      exposure:
        include: metrics,health
```

**Available metrics:**
- `pgcache.gets` (counter, tagged: `result=hit|miss`, `cache=<name>`)
- `pgcache.puts` (counter)
- `pgcache.evictions` (counter)
- `pgcache.size` (gauge)
- `pgcache.hit.rate` (gauge, 0.0 - 1.0)

**Manual binding** (without auto-configuration):
```java
MeterRegistry registry = ...;
PgCache cache = (PgCache) cacheManager.getCache("myCache");
PgCacheMetrics.monitor(cache, registry);
```

## Sliding vs Absolute TTL

```java
// Absolute TTL: expires at creation_time + TTL
cache.put("key", value, Duration.ofHours(1));
// Created at 10:00 → Expires at 11:00 (regardless of access)

// Sliding TTL: expires at last_access_time + TTL
cache.put("key", value, Duration.ofHours(1), TTLPolicy.SLIDING);
// Created at 10:00, accessed at 10:30 → Expires at 11:30
// Accessed again at 11:00 → Expires at 12:00
```

## Performance

PgCache uses several optimizations:

- **UNLOGGED tables** - No WAL overhead, faster writes
- **GIN indexes** - Efficient JSONB querying
- **Cached size()** - Avoids expensive COUNT queries
- **Partial indexes** - Optimized TTL cleanup queries

**Typical performance** (on modest hardware):
- Read: ~1-5ms
- Write: ~2-10ms
- Suitable for: hundreds to low thousands of ops/sec

For comparison: Redis typically offers sub-ms latency and millions of ops/sec. Choose based on your actual needs.

## When to Use PgCache

| Use PgCache | Use Redis/Memcached |
|-------------|---------------------|
| < 100k cache entries | > 1M cache entries |
| Hundreds of ops/sec | Millions of ops/sec |
| Cost-sensitive | Performance-critical |
| Simple infrastructure | Already have Redis |
| PostgreSQL already in stack | Need pub/sub, streams |

## Database Schema

PgCache automatically creates this table:

```sql
CREATE UNLOGGED TABLE pgcache_store (
  key TEXT PRIMARY KEY,
  value JSONB NOT NULL,
  updated_at TIMESTAMP DEFAULT now(),
  ttl_seconds INT,
  ttl_policy VARCHAR(10) DEFAULT 'ABSOLUTE',
  last_accessed TIMESTAMP DEFAULT now()
);
```

## Migration

### From 1.3.x to 1.4.0

No database changes required. Just update the dependency version.

New features:
- Micrometer metrics integration (auto-configured when Micrometer is on classpath)
- `PgCacheMetrics` class for manual metric binding

### From 1.2.x to 1.3.0

No database changes required. Just update the dependency version.

New APIs available:
- `getAll()`, `putAll()`, `evictAll()` - Batch operations
- `getStatistics()`, `resetStatistics()` - Cache statistics
- `evictByPattern()` - Pattern-based eviction

### From 1.0.x/1.1.x to 1.2.x

```sql
ALTER TABLE pgcache_store
  ADD COLUMN IF NOT EXISTS ttl_policy VARCHAR(10) DEFAULT 'ABSOLUTE',
  ADD COLUMN IF NOT EXISTS last_accessed TIMESTAMP DEFAULT now();

CREATE INDEX IF NOT EXISTS pgcache_store_sliding_ttl_idx
  ON pgcache_store (ttl_policy, last_accessed)
  WHERE ttl_policy = 'SLIDING';
```

## Links

- [Changelog](CHANGELOG.md) - Version history
- [GitHub Issues](https://github.com/hunghhdev/pgcache/issues) - Bug reports & features
- [Maven Central](https://central.sonatype.com/artifact/io.github.hunghhdev/pgcache-core)

## License

MIT License - see [LICENSE](LICENSE) for details.
