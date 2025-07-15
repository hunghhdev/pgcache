# PgCache

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.1.0-green.svg)](https://search.maven.org/artifact/io.github.hunghhdev/pgcache-core)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A production-ready Java library for using PostgreSQL as a cache backend, providing both standalone API and Spring Framework integration.

## Features

### Core Features
- Use PostgreSQL as a key-value cache storage with UNLOGGED tables for optimal performance
- Simple API with get/put/evict operations and TTL (Time-To-Live) support
- **🆕 Sliding TTL**: Expiration time resets on each access, keeping active entries cached longer
- Automatic serialization/deserialization of Java objects to/from JSON
- Leverages PostgreSQL's JSONB and GIN indexes for efficient storage and querying
- Thread-safe operations with proper resource management
- Background TTL cleanup with configurable intervals
- Support for permanent entries (no expiration)
- Java 11+ compatible

### Spring Integration Features
- **Spring Cache Abstraction**: Full implementation of Spring's `Cache` and `CacheManager` interfaces
- **Auto-Configuration**: Zero-configuration setup with Spring Boot
- **Annotation Support**: Works with `@Cacheable`, `@CacheEvict`, `@CachePut`, and other Spring cache annotations
- **Configuration Properties**: YAML/Properties configuration support
- **Health Monitoring**: Spring Boot Actuator integration for health checks and metrics
- **Multiple Cache Configuration**: Per-cache TTL and settings

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
  <version>1.1.0</version>
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
  <version>1.1.0</version>
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
        // This method will be cached automatically
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

#### Configuration

```yaml
# application.yml
pgcache:
  default-ttl: PT1H              # 1 hour TTL by default
  allow-null-values: true        # Allow caching null values
  background-cleanup:
    enabled: true               # Enable background cleanup
    interval: PT30M             # Cleanup every 30 minutes
  
  # Per-cache configuration
  caches:
    users:
      ttl: PT2H                 # 2 hour TTL for users cache
    products:
      ttl: PT15M                # 15 minute TTL for products
```

For database schema details and implementation specifics, please refer to the [pgcache-core module README](pgcache-core/README.md).

## Why use PostgreSQL as a cache?

### Advantages over dedicated cache engines (Redis, Memcached, etc.)

- **Simplified architecture**: Eliminate the need for separate cache infrastructure, reducing operational complexity
- **Built-in persistence**: Cache data is automatically persistent with configurable durability levels
- **Transactional integrity**: Cache operations can participate in database transactions
- **Rich query capabilities**: Use SQL and JSONB operations for advanced cache querying beyond key-value lookups
- **Familiar tooling**: Leverage existing PostgreSQL monitoring, backup, and management tools
- **Security integration**: Utilize PostgreSQL's robust security features and existing authentication mechanisms
- **No additional licensing costs**: Use your existing PostgreSQL licenses

### Technical advantages

- **JSONB performance**: PostgreSQL's JSONB type offers excellent performance for structured data
- **GIN indexing**: Efficient querying of JSON data through optimized indexing
- **ACID guarantees**: Cache operations can have full ACID properties when needed
- **Advanced TTL management**: Leverage PostgreSQL's built-in timestamp and interval types
- **Sliding TTL support**: First PostgreSQL cache with sliding TTL, matching Redis capabilities
- **Horizontal scaling**: Use PostgreSQL's replication features for scaling

## Sliding TTL Benefits

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

- You already have PostgreSQL in your stack
- You want to reduce infrastructure complexity
- Your cache needs ACID guarantees
- You need rich querying capabilities beyond simple key-value lookups
- You want to avoid managing additional cache systems

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

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Acknowledgements

- This project was inspired by the need for a simpler cache solution that leverages existing database infrastructure.
