# PgCache

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.9.1-green.svg)](https://central.sonatype.com/artifact/io.github.hunghhdev/pgcache)
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
- **Spring Boot integration** - Works with `@Cacheable`, `@CacheEvict`, auto-configuration
- **Quarkus integration** - CDI injection, Mutiny async API, MicroProfile Health support (programmatic API — `@CacheResult` annotations are not routed to PgCache yet, planned for 2.0)
- **Cache-stampede protection (v1.9.0)** - `getOrCompute` loads each missing key exactly once across threads *and JVMs* (PostgreSQL advisory locks — something plain Redis cannot do)
- **Atomic operations (v1.9.0)** - `increment`/`decrement`, `getAndDelete`, `getAndPut`, `persist`, `expireAt` (Redis INCR/GETDEL/GETSET/PERSIST/EXPIREAT parity)
- **Typed generic reads (v1.9.0)** - `get(key, new TypeReference<List<User>>() {})`
- **Key scanning (v1.9.0)** - `scanKeys(pattern, batchSize)` streams keys with constant memory
- **Namespaces (v1.9.0)** - isolated logical caches sharing one table
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
  <version>1.9.1</version>
</dependency>

<!-- Spring Boot integration -->
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-spring</artifactId>
  <version>1.9.1</version>
</dependency>

<!-- Quarkus integration -->
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-quarkus</artifactId>
  <version>1.9.1</version>
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
        PgQuarkusCache cache = (PgQuarkusCache) cacheManager.getCache("users").get();
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

### Read-through with stampede protection (v1.9.0)

```java
// On a miss, exactly ONE caller (across threads and JVMs) runs the loader;
// concurrent callers wait on a PostgreSQL advisory lock and read the stored result.
User user = cache.getOrCompute("user:42", User.class, Duration.ofMinutes(10),
    () -> userRepository.findById(42));
```

Spring's `@Cacheable(sync = true)` routes through this automatically.

### Atomic operations (v1.9.0)

```java
long views = cache.increment("views:page:1", 1);            // Redis INCR
long stock = cache.decrement("stock:sku-9", 3);             // Redis DECRBY
Optional<Token> old = cache.getAndDelete("token:abc", Token.class);  // Redis GETDEL
Optional<Object> prev = cache.getAndPut("config", newCfg, ttl, TTLPolicy.ABSOLUTE); // GETSET
cache.persist("session:1");                                 // Redis PERSIST (drop TTL)
cache.expireAt("report:daily", tomorrowMidnight);           // Redis EXPIREAT

TtlInfo info = cache.getTtlInfo("session:1");               // MISSING / PERMANENT / EXPIRING
```

### Typed generic reads (v1.9.0)

```java
Optional<List<User>> users = cache.get("team:all", new TypeReference<List<User>>() {});
```

### Key scanning (v1.9.0)

```java
// Streams keys in batches of 500 — constant memory even with millions of keys
for (String key : cache.scanKeys("user:%", 500)) {
    process(key);
}
```

### Namespaces (v1.9.0)

```java
PgCacheStore tenantA = PgCacheStore.builder()
    .dataSource(ds).namespace("tenant_a").build();
PgCacheStore tenantB = PgCacheStore.builder()
    .dataSource(ds).namespace("tenant_b").build();

// Same table, fully isolated: clear(), size(), getKeys(), scanKeys()
// only see the store's own namespace
tenantA.clear(); // tenant_b entries untouched
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

### UNLOGGED table semantics

UNLOGGED tables are what make PgCache fast, and they behave like a cache should — but be aware:

- **Crash recovery truncates the table.** After a PostgreSQL crash (not a clean restart), all cache entries are gone. Your application must treat every read as a potential miss — which is true of any cache.
- **Streaming replicas see an empty table.** UNLOGGED data is not replicated; if you route reads to a hot standby, every cache read there will miss. Point PgCache at the primary.

### Error-handling strategy

- **Reads degrade**: if the database is unreachable, `get` behaves as a cache miss (logged as a warning) so your loader path still runs.
- **Writes throw**: `put`/`evict` failures raise `PgCacheException` — silent write failures would let stale data live forever.

## Migration

### From 1.8.x to 1.9.x

No database changes required. All new APIs (`getOrCompute`, atomic operations, `TypeReference` reads, `scanKeys`, namespaces) are additive.

**Behavior changes (1.9.0):**
- Event listeners now fire for every operation: `putAll` fires `onPut` per entry, `evictAll`/`evictByPattern` fire `onEvict` per deleted key, `onClear` fires only when `clear()` removed at least one row.
- TTL expiry is counted in the new `expiredCount` statistic instead of inflating `evictionCount`.
- Spring `Cache.get(key, type)` now throws `IllegalStateException` on a type mismatch (Spring Cache contract) instead of silently returning `null`.

### From 1.7.x to 1.8.0

Timestamp columns changed from `TIMESTAMP` to `TIMESTAMPTZ` (fixes TTL math across time zones and DST). With `autoCreateTable=true` (the default), existing tables are migrated automatically at startup — a WARN is logged. To migrate manually:

```sql
ALTER TABLE pgcache_store
  ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE current_setting('TimeZone'),
  ALTER COLUMN updated_at SET DEFAULT now(),
  ALTER COLUMN last_accessed TYPE timestamptz USING last_accessed AT TIME ZONE current_setting('TimeZone'),
  ALTER COLUMN last_accessed SET DEFAULT now();
```

### From 1.6.x to 1.7.0

No database changes required. Backward-compatible release with bug fixes and DRY refactor.

**Bug fixes:**
- Cache names containing `_` or `%` no longer cause cross-cache eviction (SQL `LIKE` wildcards now escaped).
- Concurrent `setCacheConfiguration` / `removeCache` / `getCache` no longer racy.
- `PgCacheManager.removeCache` returns `true` even when post-removal `clear()` fails.

**New API:**
- `PgCacheClient.size(String pattern)` — efficient `COUNT(*)` size lookup.
- `TTLPolicy.parse(String)` / `parseOrDefault(String)`.
- `NullValueMarker.MARKER_VALUE`, `NullValueMarker.isMarker(Object)`.
- `SqlPatterns.escapeLikePattern(String)`.
- `PgCacheManager.getStoreStatistics()` — per-store metrics for health endpoints.

**Deprecations (removed in 2.0.0):**
- `PgCacheStore(DataSource[, boolean])` ctors → use `PgCacheStore.builder()`.
- `PgCacheManager.PgCacheConfiguration` → use `CacheStoreConfig`.
- `PgCache.cleanupExpired()` / `PgQuarkusCache.cleanupExpired()` → use `cleanupExpiredAllCaches()`.
- `PgQuarkusCacheManager.getOrCreateCache(String)` → use `(PgQuarkusCache) getCache(name).get()`.
- `PgQuarkusCacheManager.CacheConfig`: `isAllowNullValues()` and 3-arg ctor removed; setter changed to `setAllowNullValues(Boolean)` for 3-state semantics.

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
