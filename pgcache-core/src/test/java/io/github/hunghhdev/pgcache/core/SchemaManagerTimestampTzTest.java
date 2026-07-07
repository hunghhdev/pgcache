package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Timestamp columns must be TIMESTAMPTZ: plain TIMESTAMP stores wall-clock
 * values interpreted through each writer's session time zone, so two JVMs in
 * different zones sharing a cache table compute expiry hours apart (and every
 * DST fall-back creates a one-hour anomaly window).
 */
@Testcontainers
class SchemaManagerTimestampTzTest {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pgcache_test")
            .withUsername("test")
            .withPassword("test");

    private static PGSimpleDataSource dataSource;

    @BeforeAll
    static void setUpDataSource() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
    }

    private static Map<String, String> timestampColumnTypes(String tableName) throws SQLException {
        Map<String, String> types = new HashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT column_name, data_type FROM information_schema.columns " +
                     "WHERE table_name = ? AND column_name IN ('updated_at', 'last_accessed')")) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    types.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return types;
    }

    @Test
    void newTablesUseTimestampTzColumns() throws SQLException {
        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(dataSource)
                .tableName("tz_new_table")
                .autoCreateTable(true)
                .build()) {
            store.put("k", "v", Duration.ofMinutes(5));
        }

        Map<String, String> types = timestampColumnTypes("tz_new_table");
        assertEquals("timestamp with time zone", types.get("updated_at"));
        assertEquals("timestamp with time zone", types.get("last_accessed"));
    }

    @Test
    void legacyTimestampTablesAreMigratedOnStartup() throws SQLException {
        // Simulate a table created by an older version (TIMESTAMP, no time zone)
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE UNLOGGED TABLE IF NOT EXISTS tz_legacy_table (" +
                    "key TEXT PRIMARY KEY, value JSONB NOT NULL, " +
                    "updated_at TIMESTAMP DEFAULT now(), ttl_seconds INT, " +
                    "ttl_policy VARCHAR(10) DEFAULT 'ABSOLUTE', " +
                    "last_accessed TIMESTAMP DEFAULT now())");
            stmt.execute("INSERT INTO tz_legacy_table (key, value, updated_at, ttl_seconds) " +
                    "VALUES ('legacy-key', '\"legacy-value\"', now(), 300)");
        }

        try (PgCacheStore store = PgCacheStore.builder()
                .dataSource(dataSource)
                .tableName("tz_legacy_table")
                .autoCreateTable(true)
                .build()) {

            Map<String, String> types = timestampColumnTypes("tz_legacy_table");
            assertEquals("timestamp with time zone", types.get("updated_at"),
                    "legacy updated_at must be migrated to timestamptz");
            assertEquals("timestamp with time zone", types.get("last_accessed"),
                    "legacy last_accessed must be migrated to timestamptz");

            // The pre-existing row must survive the migration with its TTL intact
            Optional<String> legacyValue = store.get("legacy-key", String.class);
            assertTrue(legacyValue.isPresent(), "legacy row must remain readable after migration");
            assertEquals("legacy-value", legacyValue.get());

            Optional<Duration> remaining = store.getRemainingTTL("legacy-key");
            assertTrue(remaining.isPresent());
            assertTrue(remaining.get().getSeconds() > 280 && remaining.get().getSeconds() <= 300,
                    "TTL must stay ~300s after migration, was " + remaining.get());
        }
    }
}
