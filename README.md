# PgCache

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.9.2-green.svg)](https://central.sonatype.com/artifact/io.github.hunghhdev/pgcache)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**Caching for Java apps that uses your existing PostgreSQL as the backend — no Redis needed.**

Good fit: small-to-medium apps where PostgreSQL is already the primary database and Redis would be one more thing to run. Not a fit: millions of ops/sec, sub-millisecond latency, or multi-million-entry caches.

## Features

- **Zero extra infrastructure** — UNLOGGED tables + JSONB in the database you already have
- **Spring Boot** — `@Cacheable`/`@CacheEvict` via auto-configuration, Actuator health, Micrometer metrics
- **Quarkus** — CDI injection, Mutiny async API (programmatic only; `@CacheResult` support planned for 2.0)
- **Cache-stampede protection** — `getOrCompute` runs the loader exactly once across threads *and JVMs* (PostgreSQL advisory locks)
- **Redis-parity operations** — `increment`/`decrement`, `getAndDelete`, `getAndPut`, `persist`, `expireAt`, `getTtlInfo`
- **TTL policies** — ABSOLUTE (fixed) or SLIDING (reset on access), per cache or per entry
- **And the rest** — async API, batch ops, typed generic reads, `scanKeys` streaming, namespaces, event listeners, statistics, pattern eviction, background cleanup

## Quick Start

```xml
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-core</artifactId>      <!-- or pgcache-spring / pgcache-quarkus -->
  <version>1.9.2</version>
</dependency>
```

### Spring Boot

```java
@SpringBootApplication
@EnablePgCache
public class Application { ... }

@Service
public class UserService {
    @Cacheable("users")
    public User getUser(Long id) { return userRepository.findById(id); }

    @CacheEvict("users")
    public void deleteUser(Long id) { userRepository.deleteById(id); }
}
```

```yaml
# application.yml
pgcache:
  default-ttl: PT1H
  background-cleanup:
    enabled: true
    interval: PT30M
  caches:
    users:
      ttl: PT2H
      ttl-policy: SLIDING
```

### Quarkus

```java
@ApplicationScoped
public class UserService {
    @Inject
    PgQuarkusCacheManager cacheManager;

    public Uni<User> getUser(Long id) {
        PgQuarkusCache cache = (PgQuarkusCache) cacheManager.getCache("users").get();
        return cache.getAsync("user:" + id, key -> userRepository.findById(id));
    }
}
```

```properties
# application.properties — same keys as Spring
pgcache.default-ttl=PT1H
pgcache.caches.users.ttl=PT2H
pgcache.caches.users.ttl-policy=SLIDING
```

### Standalone (core)

```java
PgCacheClient cache = PgCacheStore.builder()
    .dataSource(dataSource)
    .build();

cache.put("user:123", user, Duration.ofHours(1));
Optional<User> user = cache.get("user:123", User.class);
```

## API Tour

```java
// Read-through with stampede protection: on a miss, exactly ONE caller
// (across threads and JVMs) runs the loader; the rest wait and read the result.
// Spring's @Cacheable(sync = true) routes through this automatically.
User user = cache.getOrCompute("user:42", User.class, Duration.ofMinutes(10),
    () -> userRepository.findById(42));

// Atomic operations (Redis parity)
long views = cache.increment("views:page:1", 1);                     // INCR
Optional<Token> old = cache.getAndDelete("token:abc", Token.class);  // GETDEL
cache.persist("session:1");                                          // PERSIST
cache.expireAt("report:daily", tomorrowMidnight);                    // EXPIREAT

// Typed generic reads
Optional<List<User>> users = cache.get("team:all", new TypeReference<List<User>>() {});

// Key scanning — batched, constant memory
for (String key : cache.scanKeys("user:%", 500)) { process(key); }

// Batch + pattern operations
cache.putAll(entries, Duration.ofHours(1));
cache.getAll(keys, User.class);
cache.evictByPattern("user:%");

// Async
cache.getAsync("user:123", User.class).thenAccept(opt -> ...);

// Statistics
cache.getStatistics().getHitRate();
```

### Namespaces

Isolated logical caches sharing one table — `clear()`, `size()`, `getKeys()` only see their own namespace:

```java
PgCacheStore tenantA = PgCacheStore.builder().dataSource(ds).namespace("tenant_a").build();
PgCacheStore tenantB = PgCacheStore.builder().dataSource(ds).namespace("tenant_b").build();
tenantA.clear(); // tenant_b untouched
```

### Event Listeners

```java
PgCacheStore.builder()
    .dataSource(dataSource)
    .addEventListener(new CacheEventListener() {
        @Override public void onPut(String key, Object value) { log.info("Cached: {}", key); }
        @Override public void onEvict(String key) { log.info("Evicted: {}", key); }
    })
    .build();
```

In Spring Boot, just define a `CacheEventListener` bean — it's auto-detected.

## Operational Notes

**Performance.** UNLOGGED tables (no WAL) + JSONB: reads ~1–5ms, writes ~2–10ms, hundreds to low thousands ops/sec.

**UNLOGGED semantics.** After a PostgreSQL *crash* (not a clean restart) the table is truncated — treat every read as a potential miss, as with any cache. UNLOGGED data is not replicated: point PgCache at the primary, never a read replica.

**Error handling.** Reads degrade — an unreachable database behaves as a cache miss (logged) so your loader path still runs. Writes throw `PgCacheException` — silent write failures would let stale data live forever.

**Micrometer metrics** (auto-configured with `pgcache-spring`): `pgcache.gets`, `pgcache.puts`, `pgcache.evictions`, `pgcache.size`, `pgcache.hit.rate`.

## Migration

### From 1.8.x to 1.9.x

No database changes. New APIs are additive. Behavior changes:
- Event listeners fire per entry (`putAll` → `onPut` each; `evictAll`/`evictByPattern` → `onEvict` per deleted key).
- TTL expiry is counted in the new `expiredCount` statistic instead of `evictionCount`.
- Spring `Cache.get(key, type)` throws `IllegalStateException` on type mismatch (Spring contract) instead of returning `null`.

### From 1.7.x to 1.8.0

Timestamp columns changed from `TIMESTAMP` to `TIMESTAMPTZ` (fixes TTL math across time zones and DST). With `autoCreateTable=true` (the default) existing tables are migrated automatically at startup. To migrate manually:

```sql
ALTER TABLE pgcache_store
  ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE current_setting('TimeZone'),
  ALTER COLUMN updated_at SET DEFAULT now(),
  ALTER COLUMN last_accessed TYPE timestamptz USING last_accessed AT TIME ZONE current_setting('TimeZone'),
  ALTER COLUMN last_accessed SET DEFAULT now();
```

Older upgrades (1.7.0 deprecation list, 1.2.x schema change, …) are documented in [CHANGELOG.md](CHANGELOG.md).

## License

MIT License - see [LICENSE](LICENSE) for details.
