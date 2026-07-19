# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.9.1] - 2026-07-11

### Bug Fixes

- `getAndDelete` no longer destroys the entry when the value cannot be deserialized into the requested type: the `DELETE ... RETURNING` now runs in an explicit transaction that commits only after deserialization succeeds. A failed call still throws `PgCacheException`, but the entry survives and remains readable with the correct type.
- `getOrCompute` no longer permanently bypasses the cache when the stored row cannot be deserialized. Deserialization failures on the fast-path read now fall through to the single-flight lock path, which recomputes the value and overwrites the bad row — so the loader runs once, not on every call. Fail-open-to-direct-load behavior is unchanged for connectivity failures.

## [1.9.0] - 2026-07-08

Redis-parity release: the operations Redis users expect, plus cache-stampede protection Redis itself does not have.

### New Features

- **`getOrCompute(key, type, ttl, [policy,] loader)` with single-flight loading**: on a miss, a PostgreSQL transaction-scoped advisory lock guarantees exactly one caller — across threads *and JVMs* — runs the loader; everyone else waits and reads the stored result. Loader failures propagate and cache nothing; if the database is down, reads fail open to a direct load. Spring's `@Cacheable(sync=true)` / `Cache.get(key, valueLoader)` now routes through this, fixing the cache-stampede window.
- **Atomic counters**: `increment` / `decrement` (Redis INCRBY/DECRBY) — single-statement UPSERT, no lost updates under concurrency. Live counters keep their TTL; expired ones restart from the delta.
- **`getAndDelete`** (Redis GETDEL) and **`getAndPut`** (Redis GETSET) — single-statement read-and-write.
- **`persist(key)`** (Redis PERSIST) and **`expireAt(key, deadline)`** (Redis EXPIREAT, past deadline deletes) — TTL computed against the database clock.
- **`getTtlInfo(key)`** — distinguishes MISSING / PERMANENT / EXPIRING-with-remaining, which `getRemainingTTL` could not.
- **Typed generic reads**: `get(key, TypeReference)` and `getAll(keys, TypeReference)` — `List<User>` comes back as `List<User>`, not `List<LinkedHashMap>`.
- **`scanKeys(pattern, batchSize)`** — lazy keyset-paginated key iteration with constant memory (replaces materializing `getKeys` on large key sets).
- **Namespaces**: `builder().namespace("tenant_a")` transparently prefixes keys and scopes `clear`/`size`/`getKeys`/`scanKeys`/`evictByPattern`; multiple namespaced stores safely share one table.
- `cleanupExpired(batchSize)` — expired rows are now deleted in LIMIT-ed batches (default 10 000) in short transactions instead of one unbounded DELETE.
- `CacheStatistics.getExpiredCount()` — TTL expiry is counted separately from explicit evictions (Redis `expired_keys` vs `evicted_keys`).

### Behavior Changes

- Cache event listeners now fire for every operation: `putAll` fires `onPut` per entry, `evictAll`/`evictByPattern` fire `onEvict` per actually-deleted key, and `onClear` fires only when `clear()` removed at least one row.
- `cleanupExpired` no longer inflates `evictionCount`; expired removals are reported in the new `expiredCount`.
- A cached value that fails deserialization now counts as a miss in statistics (the exception is still thrown).
- Spring `Cache.get(key, type)` throws `IllegalStateException` on a type mismatch, as the Spring Cache contract requires (was: silently returned `null`). Infrastructure failures still degrade to a miss.

## [1.8.0] - 2026-07-08

### New Features

- **Spring Boot 3 support**: auto-configurations are now registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Boot 3.x) in addition to `spring.factories` (Boot 2.x). Verified in CI against Boot 3.2 on JDK 17.
- **Quarkus module works out of the box**: the jar now ships a Jandex index, so `@Inject PgQuarkusCacheManager` is discovered without `quarkus.index-dependency` configuration. `PgQuarkusHealthCheck` is injectable (CDI producer). New config: `pgcache.table-name`, `pgcache.auto-create-table`.
- Quarkus async operations run on the injected managed `ExecutorService` (fallback: Mutiny worker pool) instead of `ForkJoinPool.commonPool()`.
- `PgCacheStore.getTableName()` accessor.
- Spring: `PgCache.getCacheStatistics()` — statistics scoped to a single cache (the store-wide `getStatistics()` remains).
- CI workflow: build + test matrix on JDK 11/17/21 and a Spring Boot 3 compatibility job.

### Bug Fixes

- `putIfAbsent` is now a single atomic statement on a single connection (was: three statements, with the existing-value lookup on a second pooled connection — deadlock-prone on exhausted pools and racy under concurrency). Statistics are no longer polluted and the permanent variant fires `onPut` like the TTL variant.
- Timestamp columns are now `TIMESTAMPTZ`; tables created by older versions are migrated automatically at startup. Fixes TTL math across writer time zones and DST transitions.
- `putAll` executes in an explicit transaction — a mid-batch failure leaves no partial writes (Redis `MSET` semantics).
- `close()` deregisters the JVM shutdown hook (was leaked, pinning the store and DataSource on redeploys).
- Connection retry only applies to transient failures (SQLState `08xxx`); auth/config errors fail fast. Backoff is now truly exponential.
- Micrometer meters report per-cache counts instead of shared store-wide counters (dashboards no longer multiply by the number of caches). Meter binding is idempotent across composite registries and cache re-creation.
- Fixed a race in the cached `size()` value that could pin a stale count after a concurrent write.
- Removed extension-only `@ConfigRoot` from the Quarkus config mapping.

### Migration

Schema migration to `TIMESTAMPTZ` happens automatically on startup when `autoCreateTable=true` (a WARN is logged). To migrate manually:
```sql
ALTER TABLE pgcache_store
  ALTER COLUMN updated_at TYPE timestamptz USING updated_at AT TIME ZONE current_setting('TimeZone'),
  ALTER COLUMN updated_at SET DEFAULT now(),
  ALTER COLUMN last_accessed TYPE timestamptz USING last_accessed AT TIME ZONE current_setting('TimeZone'),
  ALTER COLUMN last_accessed SET DEFAULT now();
```

## [1.7.1] - 2026-07-07

### Bug Fixes

- **Critical:** Auto table creation no longer fails for schema-qualified table names (`app.cache`): index names are now plain identifiers as PostgreSQL requires.
- **Critical:** Spring auto-configuration backs off correctly — it no longer crashes apps without a `DataSource` bean (`@ConditionalOnBean` added) and no longer collides with a user-defined `CacheManager` (back-off is now by type, not by bean name). The dead `cacheManager()` alias bean was removed.
- **Critical:** Writes are no longer silently lost on connection pools configured with `auto-commit=false` — connections are switched to autocommit before use (including DDL).
- `PgCacheHealthIndicator` is now actually registered via the new `PgCacheHealthAutoConfiguration` (it was previously dead code — `@Component` without a component scan).
- `refreshTTL` no longer resurrects logically expired rows awaiting cleanup; it returns `false`, matching `get()` semantics.
- Sliding-TTL reads refresh `last_accessed` atomically in the same statement/connection as the read: no second pool checkout (fixes silent refresh failures on exhausted pools) and no re-arming of already-expired rows.
- Quarkus: per-cache `allow-null-values=true` now works when the global default is `false`.
- Sub-minute `background-cleanup.interval` values (e.g. `PT30S`) no longer crash startup in Spring and Quarkus.
- `tableExists` metadata lookup escapes SQL LIKE metacharacters and folds case, fixing false positives (`pgcacheXstore` matching `pgcache_store`) and misses for uppercase table names.
- Spring: the TTL `put(key, value, ttl, policy)` overload now rethrows `PgCacheException` like the Spring-contract `put`, instead of swallowing failures.
- Spring: metrics auto-configuration references actuator classes by name, avoiding `NoClassDefFoundError` when `micrometer-core` is present without actuator.

### New Features

- `PgCacheStore.Builder.cleanupInterval(Duration)` — background cleanup now supports sub-minute intervals; non-positive intervals are rejected with a clear error.

## [1.7.0] - 2026-05-12

### Bug Fixes

- **Critical:** Cache names containing `_` or `%` no longer cause cross-cache eviction. SQL LIKE wildcards are now escaped via `LIKE ? ESCAPE '\\'`.
- **Critical:** Concurrent `setCacheConfiguration` / `removeCache` / `getCache` no longer racy. `PgCacheManager` admin operations are now synchronized on a dedicated lock.
- `PgCacheManager.removeCache` correctly returns `true` even when post-removal `clear()` fails. Order also corrected so `clear()` runs before store decref.

### New Features

- `PgCacheClient.size(String pattern)` — efficient COUNT-based size lookup with default fallback for external implementers.
- `TTLPolicy.parse(String)` and `TTLPolicy.parseOrDefault(String)` — shared cross-module parsing helpers.
- `NullValueMarker.MARKER_VALUE` constant and `NullValueMarker.isMarker(Object)` predicate.
- `SqlPatterns.escapeLikePattern(String)` — utility for escaping SQL LIKE meta-characters.
- `PgCacheManager.getStoreStatistics()` — per-store statistics for health endpoints.
- `PgCacheHealthIndicator` now reports stats per underlying store (no longer assumes "first cache" represents all).

### Deprecations (removal in 2.0.0)

- `PgCacheStore(DataSource, boolean)` and `PgCacheStore(DataSource)` constructors — use `PgCacheStore.builder()`.
- `PgCacheManager.PgCacheConfiguration` (nested) — use `CacheStoreConfig`.
- `PgCache.cleanupExpired()` — use `cleanupExpiredAllCaches()` (clearer scope: store-wide, not cache-scoped).
- `PgQuarkusCache.cleanupExpired()` — use `cleanupExpiredAllCaches()`.
- `PgQuarkusCacheManager.getOrCreateCache(String)` — use `getCache(name).get()`.
- `PgQuarkusCacheManager.CacheConfig.isAllowNullValues()` removed; 3-arg constructor removed; setter changed to `setAllowNullValues(Boolean)` to support 3-state (null = inherit) semantics.

### Internal restructuring (no behavior change)

- `PgCacheStore` split: `SchemaManager` and `BackgroundCleanupScheduler` extracted as collaborators.
- DRY refactor: shared `validateKey` and `normalizeValue` helpers; magic numbers promoted to named constants; field declarations consolidated.
- `catch (Exception)` narrowed to `catch (SQLException | JsonProcessingException)` where appropriate.
- `PgCache` exception strategy unified: read methods swallow + log warn + return default; write methods rethrow `PgCacheException` (no longer raw `RuntimeException`).
- `TtlHelper` (dead code) removed.

### Migration

No database schema changes required. All deprecations remain functional through 1.7.x; remove usage before upgrading to 2.0.0.

---

## [1.6.2] - 2026-03-29

### Fixed
- Cleanup now expires legacy sliding-TTL rows written by pre-1.2.0 versions (`NULL` `ttl_policy` treated as ABSOLUTE); backward-compatible rows are no longer filtered out of reads.
- Schema-qualified table names supported for legacy sliding rows and table lookups.
- Restored Spring default table name compatibility.
- Restored Quarkus `allow-null-values` compatibility accessor.
- Spring cache config inherits the global TTL policy when no per-cache policy is set.
- Permanent `putIfAbsent` no longer blocked by an expired row under the same key.
- Fixed NPE when `updated_at` is `NULL`; fixed store usage-count leak in `setCacheConfiguration` and `storeMap` (stores now closed when the last cache is removed).
- Resource cleanup on shutdown: `DisposableBean` in Spring `PgCacheManager`, `@Disposes` in Quarkus producer.
- Spring TTL policy is configurable via `PgCacheProperties.ttlPolicy`.
- `tableName` is validated as a SQL identifier (injection guard).

## [1.6.1] - 2026-03-28

### Fixed
- `refreshTTL` preserves the entry's sliding TTL policy (was silently reset to ABSOLUTE).
- TTL conversions validated before second-level truncation (sub-second TTLs no longer become zero).
- Cache size reporting scoped per cache instead of store-wide.
- Spring value-loader exception semantics preserved (`Cache.ValueRetrievalException`).
- Quarkus: permanent entries preserved when the default TTL is unset.
- Integration cache invalidation scoped correctly; `tableName` config honored.
- Spring typed-cache logging guarded against null types.

## [1.6.0] - 2026-01-12

### Added
- **Async API**: `getAsync`, `putAsync`, `evictAsync` in `PgCacheClient` (configurable executor via `Builder.asyncExecutor()`).
- **Key operations**: `containsKey(key)`, `getKeys(pattern)`, `getAllKeys()`.
- **Event listeners**: `CacheEventListener` interface with `onPut`/`onEvict`/`onClear` callbacks; Spring auto-detects listener beans.
- **Quarkus health check**: `PgQuarkusHealthCheck` helper for MicroProfile Health.
- Quarkus `PgQuarkusCache` now uses the non-blocking async API internally.

### Changed
- Internal refactor: `CacheEventDispatcher` extracted; shared expiry `WHERE` clauses; fixed double-fetch in Spring `PgCache.get(key, type)`.

---

## [1.5.1] - 2025-12-19

### Fixed
- Optimized dependencies with `provided` scope to reduce transitive dependencies
- PostgreSQL driver, SLF4J, Spring, and Quarkus deps now `provided` scope
- Users only pull Jackson as transitive dependency (~1.5MB)

---

## [1.5.0] - 2025-12-18

### Added
- **Quarkus Integration** - New `pgcache-quarkus` module for Quarkus framework support
  - `PgQuarkusCache` implementing Quarkus Cache SPI with Mutiny Uni support
  - `PgQuarkusCacheManager` for managing multiple caches
  - CDI configuration via `@ConfigMapping` with `pgcache.*` properties
  - Background cleanup support
  - Per-cache TTL and TTL policy configuration

### Configuration (Quarkus)
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

---

## [1.4.0] - 2025-12-18

### Added
- **Micrometer Metrics Integration** - Expose cache statistics via Micrometer
  - `PgCacheMetrics` class implementing `MeterBinder`
  - Auto-configuration when Micrometer is on classpath
  - Metrics exposed:
    - `pgcache.gets` (counter, tagged by result: hit/miss)
    - `pgcache.puts` (counter)
    - `pgcache.evictions` (counter)
    - `pgcache.size` (gauge)
    - `pgcache.hit.rate` (gauge)

### Changed
- Added `micrometer-core` as optional dependency to pgcache-spring

---

## [1.3.0] - 2025-12-18

### Added
- **Batch Operations** - Efficient multi-key operations for reduced database roundtrips
  - `getAll(Collection<String> keys, Class<T> clazz)` - retrieve multiple values in one query
  - `putAll(Map<String, T> entries, Duration ttl)` - store multiple entries with TTL
  - `putAll(Map<String, T> entries, Duration ttl, TTLPolicy policy)` - store with TTL policy
  - `putAll(Map<String, T> entries)` - store permanent entries
  - `evictAll(Collection<String> keys)` - remove multiple entries in one operation

- **Cache Statistics** - Monitor cache performance with hit/miss tracking
  - `CacheStatistics` class with hit count, miss count, put count, eviction count
  - `getStatistics()` - retrieve current statistics
  - `resetStatistics()` - reset all counters to zero
  - Hit rate calculation via `getHitRate()` and `getMissRate()`

- **Pattern-Based Eviction** - Evict cache entries by key pattern
  - `evictByPattern(String pattern)` - remove all keys matching SQL LIKE pattern
  - Example: `evictByPattern("user:%")` removes all keys starting with "user:"

### Changed
- Statistics tracking integrated into all cache operations (get, put, evict)
- Internal counters use `AtomicLong` for thread-safe statistics

---

## [1.2.2] - 2025-12-17

### Fixed
- **size()** query now correctly handles sliding TTL entries (was only checking `updated_at`, now checks `last_accessed` for SLIDING policy)
- **putIfAbsent** race condition fixed with atomic PostgreSQL `ON CONFLICT DO NOTHING`

### Added
- `putIfAbsent(key, value, ttl, policy)` method in `PgCacheClient` interface for atomic conditional insert
- `putIfAbsent(key, value)` method for permanent entries

### Removed
- Outdated `pgcache-core/README.md` (main README is sufficient)

---

## [1.2.1] - 2025-12-17

### Added
- 🆕 **Null Value Caching Support** - Cache null values with `NullValueMarker` pattern
  - `allowNullValues` option in `PgCacheStore.Builder`
  - `NullValueMarker` singleton class for representing cached null values
  - Spring Cache properly handles cached null values (no infinite loops)

### Fixed
- 🔴 **CRITICAL**: Fixed null value caching causing infinite loops in Spring Cache
  - Previously cached null and cache miss both returned `Optional.empty()`
  - Now cached null returns `Optional.of(NullValueMarker)` for proper distinction
  - Spring Cache wrapper correctly recognizes and handles null markers

---

## [1.2.0] - 2025-12-02

### Added
- 🆕 **Sliding TTL Support** - First PostgreSQL cache library with Redis-like sliding expiration in Java ecosystem
  - `TTLPolicy.SLIDING` enum value for sliding TTL behavior
  - `last_accessed` timestamp column for tracking access time
  - Automatic TTL refresh on cache reads for sliding TTL entries
  - `getRemainingTTL()` and `getTTLPolicy()` methods for TTL inspection
  - `refreshTTL()` method to manually update TTL duration
- ✅ TTL policy configuration support via YAML/properties (`ttl-policy: SLIDING|ABSOLUTE`)
- ✅ `AutoCloseable` interface implementation for proper resource cleanup
- ✅ Shutdown hook registration for graceful cleanup on JVM shutdown
- ✅ Performance optimization: size() method now caches results for 5 seconds
- ✅ Enhanced logging with TRACE level for cache hits
- ✅ Connection retry logic with exponential backoff
- ✅ Partial indexes for better query performance

### Changed
- 🔄 **BREAKING**: Cleanup query now properly handles both ABSOLUTE and SLIDING TTL policies
- 🔄 Database schema updated to include `ttl_policy` and `last_accessed` columns
- 🔄 Default TTL policy is ABSOLUTE for backward compatibility
- 🔄 Enhanced error messages with more context
- 🔄 Improved thread safety with double-checked locking

### Fixed
- 🔴 **CRITICAL**: Fixed spring.factories package configuration preventing auto-configuration
  - Changed from `dev.hunghh.pgcache.spring` to `io.github.hunghhdev.pgcache.spring`
- 🔴 **CRITICAL**: Fixed cleanup query to properly expire SLIDING TTL entries
  - Now checks `last_accessed` for sliding TTL instead of `updated_at`
- 🔴Fixed redundant index on `key` column (PRIMARY KEY already creates index)
- 🟡 Fixed thread leak risk from background cleanup executor
- 🟡 Fixed size() method performance issue with large datasets

### Removed
- ❌ Redundant `pgcache_store_key_idx` index (PRIMARY KEY is sufficient)

### Security
- 📦 Recommended dependency updates (see Migration Guide)

---

## [1.1.0] - 2024-XX-XX

### Added
- Initial sliding TTL prototype
- Background cleanup mechanism
- Spring Boot auto-configuration

---

## [1.0.0] - 2024-XX-XX

### Added
- Initial release
- PostgreSQL-based caching with JSONB support
- Spring Cache abstraction implementation
- Absolute TTL support
- UNLOGGED tables for performance
- GIN indexes for JSONB queries
- Spring Boot integration
- Health indicator support
- Configurable cache properties

---

## Migration Guides

Current migration guides live in [README.md](README.md#migration). Historical guide below.

### Migrating from 1.0.0 to 1.2.0

#### Database Schema Changes

**Required**: Add new columns to existing tables

```sql
-- Add TTL policy and last_accessed columns
ALTER TABLE pgcache_store 
  ADD COLUMN IF NOT EXISTS ttl_policy VARCHAR(10) DEFAULT 'ABSOLUTE',
  ADD COLUMN IF NOT EXISTS last_accessed TIMESTAMP DEFAULT now();

-- Create index for sliding TTL queries
CREATE INDEX IF NOT EXISTS pgcache_store_sliding_ttl_idx 
  ON pgcache_store (ttl_policy, last_accessed) 
  WHERE ttl_policy = 'SLIDING';

-- Optional: Remove redundant key index
DROP INDEX IF EXISTS pgcache_store_key_idx;

-- Recommended: Add composite cleanup index
CREATE INDEX IF NOT EXISTS pgcache_store_cleanup_idx 
  ON pgcache_store (ttl_policy, last_accessed, ttl_seconds) 
  WHERE ttl_seconds IS NOT NULL;
```

#### Spring.Factories Fix

**Action Required**: If you manually copied `spring.factories`, update the package name:

```properties
# Before (WRONG)
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
dev.hunghh.pgcache.spring.PgCacheAutoConfiguration

# After (CORRECT)
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
io.github.hunghhdev.pgcache.spring.PgCacheAutoConfiguration
```

#### Configuration Changes

**No breaking changes** - All existing configurations work as-is.

**New optional configuration**:
```yaml
pgcache:
  caches:
    your-cache:
      ttl-policy: SLIDING  # NEW: Configure TTL policy per cache
```

#### API Changes

**No breaking changes** - All existing APIs work as-is with default behavior.

**New APIs available**:
```java
// New overload for get() with refresh control
Optional<T> get(String key, Class<T> clazz, boolean refreshTTL);

// New put() with TTL policy
void put(String key, T value, Duration ttl, TTLPolicy policy);

// New TTL management methods
Optional<Duration> getRemainingTTL(String key);
Optional<TTLPolicy> getTTLPolicy(String key);
boolean refreshTTL(String key, Duration newTtl);

// AutoCloseable support
try (PgCacheStore store = ...) {
    // Automatic cleanup on close
}
```

#### Behavior Changes

1. **Cleanup Logic**: Now properly expires sliding TTL entries based on `last_accessed`
2. **Size Performance**: size() calls are now cached for 5 seconds
3. **Resource Cleanup**: Background cleanup executor now shuts down properly

#### Recommended Actions

1. **Update Dependencies**:
```xml
<postgresql.version>42.7.3</postgresql.version>
<jackson.version>2.17.0</jackson.version>
```

2. **Configure Cleanup Index** (for better performance):
```sql
CREATE INDEX pgcache_store_cleanup_idx 
  ON pgcache_store (ttl_policy, last_accessed, ttl_seconds) 
  WHERE ttl_seconds IS NOT NULL;
```

3. **Test Cleanup**: Verify sliding TTL entries expire correctly
```java
// Before migration
@Test
public void testSlidingTTLCleanup() {
    cache.put("test", "value", Duration.ofSeconds(5), TTLPolicy.SLIDING);
    
    // Don't access for 6 seconds
    Thread.sleep(6000);
    
    // Run cleanup
    int cleaned = cacheStore.cleanupExpired();
    
    // Should have removed the expired entry
    assertTrue(cleaned > 0);
    assertFalse(cache.get("test", String.class).isPresent());
}
```

---

## Links

- [GitHub Repository](https://github.com/hunghhdev/pgcache)
- [Issue Tracker](https://github.com/hunghhdev/pgcache/issues)
- [Maven Central](https://search.maven.org/artifact/io.github.hunghhdev/pgcache-core)
