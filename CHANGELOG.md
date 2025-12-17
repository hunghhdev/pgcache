# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

## Deprecations

None in this release.

---

## Known Issues

1. **Null Value Support**: Even with `allowNullValues=true`, null values are not properly cached
   - **Workaround**: Avoid caching null values
   - **Fix**: Planned for 1.3.0 with null marker pattern

2. **Size Performance**: Still slow on very large datasets (>1M entries) even with caching
   - **Workaround**: Avoid frequent size() calls on large caches
   - **Fix**: Consider approximate count in future release

---

## Roadmap

### 1.3.0 (Planned)
- Null value support with marker pattern
- Batch operations API (`putAll`, `getAll`)
- Micrometer metrics integration
- Spring Boot 3 support
- Performance benchmarks

### 1.4.0 (Planned)
- Distributed cache coordination
- Cache event listeners
- Compression support for large values
- Query API for JSONB fields

---

## Contributors

- Hung Hoang (@hunghhdev) - Original author
- Community contributors - See GitHub contributors

---

## Links

- [GitHub Repository](https://github.com/hunghhdev/pgcache)
- [Issue Tracker](https://github.com/hunghhdev/pgcache/issues)
- [Maven Central](https://search.maven.org/artifact/io.github.hunghhdev/pgcache-core)
