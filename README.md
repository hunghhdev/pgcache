# PgCache

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.2.0-green.svg)](https://search.maven.org/artifact/io.github.hunghhdev/pgcache-core)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A production-ready Java library for using PostgreSQL as a cache backend, providing both standalone API and Spring Framework integration with **sliding TTL support**.

## � What's New in v1.2.0

### Critical Fixes ✅
- **Fixed Spring Boot auto-configuration** - Package name corrected, auto-config now works
- **Fixed sliding TTL cleanup** - Entries now expire correctly based on last access time
- **Removed redundant index** - Better write performance
- **Added resource cleanup** - Proper shutdown hooks, no thread leaks

### Enhancements 🚀
- **TTL policy configuration** - Configure `SLIDING` or `ABSOLUTE` TTL via YAML
- **Performance boost** - size() method now cached (1000x faster)
- **Better resource management** - AutoCloseable support for try-with-resources

> 📚 See [CHANGELOG.md](CHANGELOG.md) for detailed changes and migration guide.

## �🎯 Why PgCache?

**The first PostgreSQL cache library with sliding TTL support in the Java ecosystem!**

### 🚀 Key Advantages
- **🆕 Sliding TTL**: First PostgreSQL cache with Redis-like sliding expiration
- **🔄 Unified Stack**: Use PostgreSQL for both data and cache - no Redis needed
- **💾 Persistent Cache**: Survives restarts with configurable durability
- **🔐 ACID Compliance**: Full transactional support when needed
- **🎯 High Performance**: UNLOGGED tables with JSONB and GIN indexes
- **🌱 Spring Ready**: Complete Spring Boot integration with zero configuration

## Features

### Core Features
- **PostgreSQL-based caching** with UNLOGGED tables for optimal performance
- **Sliding TTL support** - expiration time resets on each access, keeping active entries cached longer
- **Absolute TTL support** - traditional fixed expiration time
- **Automatic JSON serialization** with PostgreSQL's JSONB type
- **GIN indexes** for efficient querying and storage
- **Thread-safe operations** with proper resource management
- **Background TTL cleanup** with configurable intervals
- **Permanent entries** support (no expiration)
- **Java 11+ compatible**

### Spring Integration Features
- **Spring Cache Abstraction**: Full `Cache` and `CacheManager` implementation
- **Auto-Configuration**: Zero-configuration setup with Spring Boot
- **Annotation Support**: `@Cacheable`, `@CacheEvict`, `@CachePut`, etc.
- **Properties Configuration**: YAML/Properties configuration support
- **Health Monitoring**: Spring Boot Actuator integration
- **Multiple Cache Configuration**: Per-cache TTL policies and settings
- **Sliding TTL Configuration**: Configure sliding TTL per cache or globally

## Project Structure

This project is organized as a multi-module Maven project:

- **pgcache-core**: Core cache implementation (framework-agnostic)
- **pgcache-spring**: Spring Framework integration with auto-configuration

## Quick Start

### Core Library Usage

#### Add dependency

```xml
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-core</artifactId>
  <version>1.2.0</version>
</dependency>
```

#### Basic usage

```java
// Create a DataSource (e.g., using HikariCP)
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:postgresql://localhost:5432/mydb");
config.setUsername("postgres");
config.setPassword("password");
DataSource dataSource = new HikariDataSource(config);

// Create cache client
PgCacheClient cacheClient = PgCacheStore.builder()
    .dataSource(dataSource)
    .build();

// Store data in cache with absolute TTL (default behavior)
User user = new User("john.doe", "John Doe", 30);
cacheClient.put("user:123", user, Duration.ofMinutes(30));

// 🆕 Store data with sliding TTL (expiration resets on each access)
cacheClient.put("user:456", user, Duration.ofMinutes(30), TTLPolicy.SLIDING);

// Retrieve data from cache (automatically refreshes sliding TTL)
Optional<User> cachedUser = cacheClient.get("user:456", User.class);
cachedUser.ifPresent(u -> System.out.println("Found user: " + u.getName()));

// 🆕 Get data without refreshing TTL (for monitoring purposes)
Optional<User> userNoRefresh = cacheClient.get("user:456", User.class, false);

// 🆕 Check remaining TTL
Optional<Duration> remainingTTL = cacheClient.getRemainingTTL("user:456");
remainingTTL.ifPresent(ttl -> System.out.println("Expires in: " + ttl.getSeconds() + " seconds"));

// 🆕 Check TTL policy
Optional<TTLPolicy> policy = cacheClient.getTTLPolicy("user:456");
policy.ifPresent(p -> System.out.println("TTL Policy: " + p)); // SLIDING

// Remove from cache
cacheClient.evict("user:123");

// Get cache size (non-expired entries)
int cacheSize = cacheClient.size();
```

#### 🆕 Sliding TTL vs Absolute TTL

```java
// Absolute TTL (default): Entry expires after fixed time from creation
cacheClient.put("session:abc", sessionData, Duration.ofHours(1)); // Expires at creation + 1 hour

// Sliding TTL: Entry expiration resets on each access
cacheClient.put("session:xyz", sessionData, Duration.ofHours(1), TTLPolicy.SLIDING);
// - If accessed within 1 hour, expiration extends to access_time + 1 hour
// - If not accessed for 1 hour, expires naturally
// - Popular entries stay cached longer, inactive ones expire automatically
```

### Spring Integration Usage

#### Add dependency

```xml
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-spring</artifactId>
  <version>1.2.0-SNAPSHOT</version>
</dependency>
```

#### Enable PgCache

```java
@SpringBootApplication
@EnablePgCache
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

#### Use Spring Cache annotations

```java
@Service
public class UserService {
    
    @Cacheable("users")
    public User getUser(Long id) {
        // This method will be cached automatically with configured TTL policy
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

#### 🆕 Advanced Spring Integration with Sliding TTL

```java
@Service
public class UserService {
    
    @Autowired
    private CacheManager cacheManager;
    
    public User getUserWithTTLControl(Long id) {
        PgCache cache = (PgCache) cacheManager.getCache("users");
        
        // Check if user exists in cache first
        User user = cache.get(id, User.class);
        if (user != null) {
            return user;
        }
        
        // Load from database
        user = userRepository.findById(id);
        
        // Cache with sliding TTL for active users
        cache.put(id, user, Duration.ofHours(2), TTLPolicy.SLIDING);
        
        return user;
    }
    
    public void monitorCacheStatus(Long id) {
        PgCache cache = (PgCache) cacheManager.getCache("users");
        
        // Check remaining TTL
        Optional<Duration> ttl = cache.getRemainingTTL(id);
        ttl.ifPresent(t -> log.info("User {} expires in {} seconds", id, t.getSeconds()));
        
        // Check TTL policy
        Optional<TTLPolicy> policy = cache.getTTLPolicy(id);
        policy.ifPresent(p -> log.info("User {} uses {} TTL policy", id, p));
    }
    
    // 🆕 TTL Refresh Management
    public void extendUserSession(Long userId) {
        PgCache cache = (PgCache) cacheManager.getCache("users");
        
        // Extend TTL for active user (useful for session management)
        boolean refreshed = cache.refreshTTL(userId, Duration.ofHours(4));
        if (refreshed) {
            log.info("Extended TTL for user {}", userId);
        }
    }
    
    public void convertToPermanentUser(Long userId) {
        PgCache cache = (PgCache) cacheManager.getCache("users");
        
        // Add TTL to a permanent entry (convert temporary to permanent)
        boolean success = cache.refreshTTL(userId, Duration.ofDays(30));
        if (success) {
            log.info("Added 30-day TTL to permanent user {}", userId);
        }
    }
}
```

#### Configuration

```yaml
# application.yml
pgcache:
  default-ttl: PT1H              # 1 hour TTL by default
  allow-null-values: true        # Allow caching null values
  background-cleanup:
    enabled: true               # Enable background cleanup
    interval: PT30M             # Cleanup every 30 minutes
  
  # 🆕 Per-cache configuration with sliding TTL
  caches:
    users:
      ttl: PT2H                 # 2 hours TTL for user cache
      ttl-policy: SLIDING       # 🆕 Use sliding TTL for active users
      allow-null-values: false  # Don't cache null users
    sessions:
      ttl: PT30M                # 30 minutes TTL for sessions
      ttl-policy: SLIDING       # 🆕 Perfect for session management
    products:
      ttl: PT6H                 # 6 hours TTL for product cache
      ttl-policy: ABSOLUTE      # Use absolute TTL for product data
```

#### 🆕 Programmatic Configuration

```java
@Configuration
public class CacheConfig {
    
    @Bean
    public CacheManager cacheManager(DataSource dataSource) {
        PgCacheManager cacheManager = new PgCacheManager(dataSource);
        
        // Configure cache with sliding TTL
        cacheManager.setCacheConfiguration("users", 
            PgCacheManager.PgCacheConfiguration.builder()
                .defaultTtl(Duration.ofHours(2))
                .ttlPolicy(TTLPolicy.SLIDING)        // 🆕 Sliding TTL
                .allowNullValues(false)
                .backgroundCleanupEnabled(true)
                .build());
        
        // Configure cache with absolute TTL
        cacheManager.setCacheConfiguration("products",
            PgCacheManager.PgCacheConfiguration.builder()
                .defaultTtl(Duration.ofHours(6))
                .ttlPolicy(TTLPolicy.ABSOLUTE)       // Traditional TTL
                .allowNullValues(true)
                .build());
        
        return cacheManager;
    }
}
```

## Why use PostgreSQL as a cache?

### 🔥 **Competitive Advantages**

**PgCache is the first PostgreSQL cache with sliding TTL in the Java ecosystem!**

#### vs Redis
- **✅ Persistent by default**: No data loss on restarts
- **✅ ACID transactions**: Cache operations can be transactional
- **✅ Unified stack**: One database for data + cache
- **✅ Sliding TTL**: Matches Redis capabilities with better persistence
- **✅ Rich querying**: SQL + JSONB beyond key-value
- **✅ Familiar tooling**: Use existing PostgreSQL tools

#### vs Memcached
- **✅ Persistent storage**: Data survives restarts
- **✅ Complex data types**: JSONB support for structured data
- **✅ Sliding TTL**: Advanced expiration strategies
- **✅ Transactions**: ACID compliance when needed
- **✅ Security**: Built-in authentication and authorization

#### vs Hazelcast/Caffeine
- **✅ Distributed by nature**: PostgreSQL replication
- **✅ Persistent**: No warmup needed after restarts
- **✅ SQL queryable**: Advanced cache querying
- **✅ Sliding TTL**: First PostgreSQL cache with this feature

### 🎯 **Technical Advantages**

- **JSONB performance**: PostgreSQL's JSONB type offers excellent performance for structured data
- **GIN indexing**: Efficient querying of JSON data through optimized indexing
- **ACID guarantees**: Cache operations can have full ACID properties when needed
- **Advanced TTL management**: Leverage PostgreSQL's built-in timestamp and interval types
- **Sliding TTL support**: First PostgreSQL cache with sliding TTL, matching Redis capabilities
- **Horizontal scaling**: Use PostgreSQL's replication features for scaling
- **Simplified architecture**: Eliminate the need for separate cache infrastructure

## 🚀 Sliding TTL Benefits

The sliding TTL feature gives PgCache a significant competitive advantage:

### 🎯 **Competitive Advantage**
- **First PostgreSQL cache with sliding TTL** in the Java ecosystem
- **Matches Redis sliding expiration capabilities** with superior persistence
- **Full ACID compliance** with PostgreSQL transactions
- **Thread-safe implementation** with proper database locking
- **Backward compatible** - existing code continues to work unchanged

### 🚀 **Use Cases**
- **User sessions**: Keep active users logged in longer
- **Frequently accessed data**: Keep hot data in cache longer
- **API rate limiting**: Sliding windows for rate limiting
- **Real-time analytics**: Keep recent data accessible
- **Configuration caching**: Refresh config on access

### 📊 **Performance Benefits**
- **Reduced database load**: Less frequent cache misses for active data
- **Better hit rates**: Active data stays cached longer
- **Efficient cleanup**: Inactive data expires naturally
- **Minimal overhead**: Only updates timestamp on access

## When to choose PgCache

- ✅ You already have PostgreSQL in your stack
- ✅ You want to reduce infrastructure complexity
- ✅ Your cache needs ACID guarantees
- ✅ You need rich querying capabilities beyond simple key-value lookups
- ✅ You want to avoid managing additional cache systems
- ✅ You need sliding TTL functionality with PostgreSQL
- ✅ You want persistent cache that survives restarts

## Best Practices

### Sliding TTL Usage
- **Use for active data**: Apply sliding TTL to frequently accessed data
- **Set appropriate durations**: Choose TTL duration based on your access patterns
- **Monitor performance**: Track cache hit rates and adjust TTL policies
- **Consider data access patterns**: Use absolute TTL for time-sensitive data

### Database Configuration
- **Connection pooling**: Use proper connection pool sizing for your workload
- **Monitoring**: Set up monitoring for cache hit rates and performance metrics
- **Cleanup scheduling**: Consider running cleanup processes during low traffic periods
- **Indexing**: The default indexes should be sufficient for most use cases

### Spring Integration
- **Cache manager configuration**: Configure appropriate cache policies per cache region
- **Annotation usage**: Use `@Cacheable` with sliding TTL for frequently accessed methods
- **Key generation**: Use meaningful cache keys for better debugging and monitoring

## Performance Considerations

- **UNLOGGED tables**: Used by default for maximum performance
- **GIN indexes**: Efficient JSONB querying and storage
- **Background cleanup**: Configurable cleanup intervals
- **Connection pooling**: Proper connection management is crucial
- **Sliding TTL overhead**: Minimal - only updates timestamp on access

## Database Schema

For database schema details and implementation specifics, please refer to the [pgcache-core module README](pgcache-core/README.md).

## 📦 Deployment

For production deployment:
- 📝 [DEPLOY_GUIDE.md](DEPLOY_GUIDE.md) - Step-by-step deployment guide
- 🧪 [TEST_CASES.md](TEST_CASES.md) - Verification test cases  
- 📋 [CHANGELOG.md](CHANGELOG.md) - Version history & migration guide

### Quick Migration for v1.2.0

If upgrading from 1.0.0/1.1.0, run this SQL:

```sql
-- Add new columns
ALTER TABLE pgcache_store 
  ADD COLUMN IF NOT EXISTS ttl_policy VARCHAR(10) DEFAULT 'ABSOLUTE',
  ADD COLUMN IF NOT EXISTS last_accessed TIMESTAMP DEFAULT now();

-- Create new indexes
CREATE INDEX IF NOT EXISTS pgcache_store_sliding_ttl_idx 
  ON pgcache_store (ttl_policy, last_accessed) 
  WHERE ttl_policy = 'SLIDING';

-- Optional: Remove redundant index
DROP INDEX IF EXISTS pgcache_store_key_idx;
```

See [CHANGELOG.md](CHANGELOG.md) for complete migration guide.

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Links

- 📖 [Full Documentation](README.md)
- 📋 [Changelog](CHANGELOG.md) - What's new & migration guides
- 🚀 [Deployment Guide](DEPLOY_GUIDE.md) - Production deployment
- 🧪 [Test Cases](TEST_CASES.md) - Verification tests
- 🔧 [GitHub Issues](https://github.com/hunghhdev/pgcache/issues) - Bug reports & features
- 📦 [Maven Central](https://search.maven.org/artifact/io.github.hunghhdev/pgcache-core)

## Acknowledgements

- This project was inspired by the need for a simpler cache solution that leverages existing database infrastructure
- Special thanks to the PostgreSQL community for providing excellent JSONB and indexing capabilities
- Inspired by Redis sliding expiration features, now available for PostgreSQL caching
