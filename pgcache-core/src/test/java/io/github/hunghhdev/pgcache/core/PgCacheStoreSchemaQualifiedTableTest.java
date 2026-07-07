package io.github.hunghhdev.pgcache.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that auto table creation works with a schema-qualified table name
 * (e.g. {@code app.cache_entries}). Index names must remain plain identifiers —
 * PostgreSQL rejects schema-qualified index names in CREATE INDEX.
 */
@Testcontainers
class PgCacheStoreSchemaQualifiedTableTest {

    private static final String SCHEMA = "app";
    private static final String TABLE = SCHEMA + ".cache_entries";

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void createSchema() throws SQLException {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        }
    }

    @Test
    void shouldAutoCreateSchemaQualifiedTableWithIndexesAndServeReads() throws SQLException {
        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(dataSource)
                .objectMapper(new ObjectMapper())
                .tableName(TABLE)
                .autoCreateTable(true)
                .build()) {

            store.put("schema-key", "schema-value", Duration.ofMinutes(5));
            Optional<String> value = store.get("schema-key", String.class);

            assertTrue(value.isPresent());
            assertEquals("schema-value", value.get());
        }

        // All three secondary indexes must exist in the table's schema
        List<String> indexNames = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT indexname FROM pg_indexes " +
                     "WHERE schemaname = '" + SCHEMA + "' AND tablename = 'cache_entries'")) {
            while (rs.next()) {
                indexNames.add(rs.getString(1));
            }
        }

        assertTrue(indexNames.contains("cache_entries_value_gin_idx"),
                "GIN index missing; found: " + indexNames);
        assertTrue(indexNames.contains("cache_entries_ttl_idx"),
                "TTL index missing; found: " + indexNames);
        assertTrue(indexNames.contains("cache_entries_sliding_ttl_idx"),
                "Sliding TTL index missing; found: " + indexNames);
    }
}
