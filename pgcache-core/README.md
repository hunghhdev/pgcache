# pgcache-core

## Purpose
`pgcache-core` is a Java library that enables using PostgreSQL as a key-value cache backend, with a simple and familiar API similar to Redis. It is framework-agnostic (no Spring dependency) and easy to integrate into any Java application.

## Motivation
- Popular Java cache solutions (Redis, Ehcache, Caffeine, etc.) do not leverage PostgreSQL as a cache backend.
- `pgcache-core` helps utilize existing PostgreSQL infrastructure, reducing operational cost and providing a simple, effective cache experience.

## Database Schema
```sql
CREATE TABLE pgcache_store (
  key TEXT PRIMARY KEY,
  value JSONB NOT NULL,
  updated_at TIMESTAMP DEFAULT now(),
  ttl_seconds INT DEFAULT 60
);
```

## Main Components

### 1. PgCacheClient (interface)
- The main API for cache operations in your application.
- Generic, type-safe, and supports per-entry TTL.
- Methods:
  - `<T> Optional<T> get(String key, Class<T> clazz);`
  - `<T> void put(String key, T value, Duration ttl);`
  - `void evict(String key);`
  - `void clear();`
  - `int size();`

### 2. PgCacheStore (class, implements PgCacheClient)
- The only implementation of PgCacheClient, directly interacts with PostgreSQL.
- Responsible for serialization/deserialization, TTL management, data mapping, and PostgreSQL connection/queries.

### 3. PgCacheException (class)
- Exception for cache-related errors.

## Overall Flow
```
[Application] -> PgCacheClient (API) -> PgCacheStore (PostgreSQL)
```

## Example Usage
```java
PgCacheClient client = PgCacheStore.builder()
    .dataSource(yourDataSource)
    .objectMapper(customObjectMapper) // optional
    .autoCreateTable(true) // optional, defaults to true
    .build();

client.put("user:1", userObject, Duration.ofMinutes(5));
Optional<User> user = client.get("user:1", User.class);
client.evict("user:1");
client.clear();
int count = client.size();
```

## Notes
- The library focuses solely on PostgreSQL as the cache backend.
- No Spring or other backend support.
- Serialization/deserialization is handled by the implementation (e.g., Jackson, Gson, ...).
- Minimum Java version: 17

---

For questions or contributions, please open an issue or PR.
