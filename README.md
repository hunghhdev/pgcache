# PgCache

[![Java](https://img.shields.io/badge/Java-11%2B-blue.svg)](https://www.oracle.com/java/)
[![Maven Central](https://img.shields.io/badge/Maven%20Central-Coming%20Soon-orange.svg)](https://search.maven.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A Java library for using PostgreSQL as a cache backend, leveraging your existing PostgreSQL infrastructure instead of requiring separate cache engines.

## Features

- Use PostgreSQL as a key-value cache storage with UNLOGGED tables for optimal performance
- Simple API with get/put/evict operations
- Support for TTL (Time-To-Live) on cache entries
- Automatic serialization/deserialization of Java objects to/from JSON
- Leverages PostgreSQL's JSONB and GIN indexes for efficient storage and querying
- No external dependencies outside of PostgreSQL and Jackson
- Java 11+ compatible

## Project Structure

This project is organized as a multi-module Maven project:

- **pgcache-core**: Core cache implementation (no Spring dependency)
- **pgcache-spring**: *(Coming soon)* Spring Cache integration

## Quick Start

### Add dependency

```xml
<dependency>
  <groupId>dev.hunghh</groupId>
  <artifactId>pgcache-core</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

### Basic usage

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
