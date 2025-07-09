# pgcache-core

## Overview
This is the core module of the PgCache library, providing a framework-agnostic implementation for using PostgreSQL as a cache backend. It contains all the essential components needed to store, retrieve, and manage cached data in PostgreSQL.

## Technical Details

### Database Schema
```sql
CREATE TABLE pgcache_store (
  key TEXT PRIMARY KEY,
  value JSONB NOT NULL,
  updated_at TIMESTAMP DEFAULT now(),
  ttl_seconds INT DEFAULT 60
);

CREATE INDEX pgcache_store_key_idx ON pgcache_store (key);
CREATE INDEX pgcache_store_value_gin_idx ON pgcache_store USING GIN (value jsonb_path_ops);
```

### Core Components

#### 1. PgCacheClient (interface)
- The main API for cache operations in your application.
- Generic, type-safe, and supports per-entry TTL.
- Methods:
  - `<T> Optional<T> get(String key, Class<T> clazz);`
  - `<T> void put(String key, T value, Duration ttl);`
  - `void evict(String key);`
  - `void clear();`
  - `int size();`

#### 2. PgCacheStore (class, implements PgCacheClient)
- The only implementation of PgCacheClient, directly interacts with PostgreSQL.
- Responsible for serialization/deserialization, TTL management, data mapping, and PostgreSQL connection/queries.
- Uses GIN index with jsonb_path_ops for optimized JSONB queries.
- Builder pattern for easy configuration.

#### 3. PgCacheException (class)
- Exception for cache-related errors.

### Implementation Details

- Uses `JSONB` PostgreSQL data type for flexible storage of any Java object
- Leverages Jackson for object serialization/deserialization
- Uses PostgreSQL-specific `ON CONFLICT` syntax for efficient upserts
- GIN index with `jsonb_path_ops` for optimized queries
- Automatic table and index creation
- Thread-safe implementation

### Usage Example
```java
// Create using builder pattern
PgCacheClient client = PgCacheStore.builder()
    .dataSource(yourDataSource)
    .objectMapper(customObjectMapper) // optional
    .autoCreateTable(true) // optional, defaults to true
    .build();

// Store data with 5-minute TTL
client.put("user:1", userObject, Duration.ofMinutes(5));

// Retrieve data
Optional<User> user = client.get("user:1", User.class);

// Other operations
client.evict("user:1"); // Remove specific key
client.clear();         // Clear all cache
int count = client.size(); // Count non-expired entries
```

### Dependencies
- Java 11+
- PostgreSQL JDBC Driver
- Jackson for JSON processing
- SLF4J for logging

See the parent README for general information about the PgCache project.
