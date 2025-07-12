# PgCache

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-1.0.0-green.svg)](https://search.maven.org/artifact/io.github.hunghhdev/pgcache-core)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A production-ready Java library for using PostgreSQL as a cache backend, providing both standalone API and Spring Framework integration.

## Features

### Core Features
- Use PostgreSQL as a key-value cache storage with UNLOGGED tables for optimal performance
- Simple API with get/put/evict operations and TTL (Time-To-Live) support
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
  <version>1.0.0</version>
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

// Store data in cache
User user = new User("john.doe", "John Doe", 30);
cacheClient.put("user:123", user, Duration.ofMinutes(30));

// Retrieve data from cache
Optional<User> cachedUser = cacheClient.get("user:123", User.class);
cachedUser.ifPresent(u -> System.out.println("Found user: " + u.getName()));

// Remove from cache
cacheClient.evict("user:123");

// Get cache size (non-expired entries)
int cacheSize = cacheClient.size();
```

### Spring Integration Usage

#### Add dependency

```xml
<dependency>
  <groupId>io.github.hunghhdev</groupId>
  <artifactId>pgcache-spring</artifactId>
  <version>1.0.0</version>
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
- **Horizontal scaling**: Use PostgreSQL's replication features for scaling

## When to choose PgCache

- You already have PostgreSQL in your stack
- You want to reduce infrastructure complexity
- Your cache needs ACID guarantees
- You need rich querying capabilities beyond simple key-value lookups
- You want to avoid managing additional cache systems

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Acknowledgements

- This project was inspired by the need for a simpler cache solution that leverages existing database infrastructure.
