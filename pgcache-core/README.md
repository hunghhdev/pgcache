# pgcache-core

## Overview
This is the core module of the PgCache library, providing a framework-agnostic implementation for using PostgreSQL as a cache backend. It contains all the essential components needed to store, retrieve, and manage cached data in PostgreSQL with advanced features like TTL cleanup and permanent cache entries.

## Key Features

- **Flexible TTL Management**: Support both temporary and permanent cache entries
- **Automatic TTL Cleanup**: Background cleanup and manual cleanup of expired entries
- **Thread-Safe Operations**: Concurrent access with double-checked locking
- **Resource Leak Prevention**: Proper connection and resource management
- **High Performance**: UNLOGGED tables and optimized indexes
- **Type Safety**: Generic API with Jackson JSON serialization

## Technical Details

### Database Schema
```sql
CREATE UNLOGGED TABLE pgcache_store (
  key TEXT PRIMARY KEY,
  value JSONB NOT NULL,
  updated_at TIMESTAMP DEFAULT now(),
  ttl_seconds INT  -- Allows NULL for permanent entries
);

-- Indexes for optimal performance
CREATE INDEX pgcache_store_key_idx ON pgcache_store (key);
CREATE INDEX pgcache_store_value_gin_idx ON pgcache_store USING GIN (value jsonb_path_ops);
CREATE INDEX pgcache_store_ttl_idx ON pgcache_store (updated_at, ttl_seconds) 
  WHERE ttl_seconds IS NOT NULL;  -- Only index entries that can expire
```

> **Note**: Uses UNLOGGED table for optimal cache performance. UNLOGGED tables are faster but data is lost on server restart, which is acceptable for cache use cases.

### Core Components

#### 1. PgCacheClient (interface)
- The main API for cache operations in your application.
- Generic, type-safe, and supports both temporary and permanent entries.
- Methods:
  - `<T> Optional<T> get(String key, Class<T> clazz);`
  - `<T> void put(String key, T value, Duration ttl);` - Temporary entry with TTL
  - `<T> void put(String key, T value);` - **NEW**: Permanent entry (no TTL)
  - `void evict(String key);`
  - `void clear();`
  - `int size();` - Counts non-expired entries

#### 2. PgCacheStore (class, implements PgCacheClient)
- The only implementation of PgCacheClient, directly interacts with PostgreSQL.
- **Enhanced Features**:
  - **TTL Cleanup**: Manual (`cleanupExpired()`) and optional background cleanup
  - **NULL TTL Support**: Permanent entries that persist until manual eviction
  - **Connection Retry Logic**: Resilient to transient connection failures
  - **Builder Pattern**: Configurable background cleanup and intervals
- Uses GIN index with jsonb_path_ops for optimized JSONB queries.
- **Additional Methods**:
  - `int cleanupExpired()` - Manually cleanup expired entries
  - `void shutdown()` - Gracefully shutdown background cleanup

#### 3. PgCacheException (class)
- Exception for cache-related errors.

### TTL and Cleanup Management

#### Flexible TTL Strategies
```java
// Temporary cache entry (expires after TTL)
cache.put("session:123", userSession, Duration.ofMinutes(30));

// Permanent cache entry (no expiration)
cache.put("config:app", applicationConfig);  // No TTL - permanent

// Mixed strategy in same cache
cache.put("temp_data", data, Duration.ofHours(1));    // Expires
cache.put("system_data", sysData);                    // Permanent
```

#### Automatic Cleanup
```java
// Enable background cleanup (runs every 5 minutes by default)
PgCacheStore cache = PgCacheStore.builder()
    .dataSource(dataSource)
    .enableBackgroundCleanup(true)
    .cleanupIntervalMinutes(10)  // Custom interval
    .build();

// Manual cleanup
int cleanedUp = cache.cleanupExpired();
System.out.println("Cleaned up " + cleanedUp + " expired entries");

// Shutdown gracefully
cache.shutdown();
```

### Implementation Details

- **NULL TTL Handling**: Entries with `ttl_seconds = NULL` never expire
- **Efficient Cleanup**: Only entries with TTL are considered for expiration
- **Database-side Logic**: Uses PostgreSQL interval arithmetic for efficient queries
- **Optimized Indexes**: TTL index only covers entries that can expire
- **Thread-safe implementation** with double-checked locking for initialization
- **Resource Management**: Proper connection handling with retry logic
- Uses `JSONB` PostgreSQL data type for flexible storage of any Java object
- Leverages Jackson for object serialization/deserialization
- Uses PostgreSQL-specific `ON CONFLICT` syntax for efficient upserts

### Configuration and Usage

#### Basic Configuration
```java
// Simple setup with auto table creation
PgCacheStore cache = new PgCacheStore(dataSource);

// Or using builder for more control
PgCacheClient client = PgCacheStore.builder()
    .dataSource(yourDataSource)
    .objectMapper(customObjectMapper)     // optional
    .autoCreateTable(true)               // optional, defaults to true
    .enableBackgroundCleanup(false)      // optional, defaults to false
    .cleanupIntervalMinutes(5)           // optional, defaults to 5
    .build();
```

#### Advanced Configuration with Background Cleanup
```java
PgCacheStore cache = PgCacheStore.builder()
    .dataSource(hikariDataSource)
    .enableBackgroundCleanup(true)       // Enable automatic cleanup
    .cleanupIntervalMinutes(10)          // Cleanup every 10 minutes
    .autoCreateTable(true)
    .build();

// Use the cache...
cache.put("temp", data, Duration.ofMinutes(5));    // Expires
cache.put("permanent", config);                    // Permanent

// Graceful shutdown (important for background cleanup)
Runtime.getRuntime().addShutdownHook(new Thread(cache::shutdown));
```

### Usage Examples

#### Basic Operations
```java
// Store temporary data (expires after 5 minutes)
client.put("user:1", userObject, Duration.ofMinutes(5));

// Store permanent data (no expiration)
client.put("config:app", appConfig);

// Retrieve data
Optional<User> user = client.get("user:1", User.class);
if (user.isPresent()) {
    System.out.println("Found user: " + user.get().getName());
}

// Cache operations
client.evict("user:1");      // Remove specific key
client.clear();              // Clear all cache
int count = client.size();   // Count non-expired entries
```

#### TTL Cleanup Operations
```java
// Manual cleanup of expired entries
int cleaned = cache.cleanupExpired();
System.out.println("Removed " + cleaned + " expired entries");

// Check cache size (only counts non-expired entries)
int activeEntries = cache.size();

// Mix temporary and permanent entries
cache.put("session:abc", session, Duration.ofHours(1));  // Temporary
cache.put("user:profile", profile);                     // Permanent

// Cleanup will only remove expired temporary entries
cache.cleanupExpired(); // Permanent entries are preserved
```

### Thread Safety

The implementation is fully thread-safe:
- **Table Initialization**: Uses double-checked locking pattern
- **Concurrent Operations**: Safe for multiple threads
- **Background Cleanup**: Uses daemon threads, doesn't block application shutdown
- **Resource Management**: Proper connection handling per operation

### Performance Considerations

1. **UNLOGGED Tables**: Faster writes, acceptable for cache use cases
2. **Efficient Indexes**: 
   - GIN index for JSONB queries
   - TTL index only for entries that can expire
   - Key index for fast lookups
3. **Database-side Expiration**: Uses PostgreSQL interval arithmetic
4. **Connection Pooling**: Use with connection pool (HikariCP recommended)
5. **Background Cleanup**: Prevents database bloat from expired entries

### Dependencies
- Java 11+
- PostgreSQL JDBC Driver
- Jackson for JSON processing
- SLF4J for logging
- TestContainers (for testing)

See the parent README for general information about the PgCache project.
