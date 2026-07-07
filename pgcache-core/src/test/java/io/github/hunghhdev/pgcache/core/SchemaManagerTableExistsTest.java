package io.github.hunghhdev.pgcache.core;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code DatabaseMetaData.getTables} treats its arguments as LIKE patterns:
 * {@code _} and {@code %} must be escaped, and PostgreSQL folds unquoted
 * identifiers to lowercase, so the lookup must be case-insensitive.
 */
@Testcontainers
class SchemaManagerTableExistsTest {

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

    @Test
    void underscoreInTableNameMustNotMatchOtherTables() throws SQLException {
        // 'pgcacheXstore' would match the pattern 'pgcache_store' if '_' were
        // treated as a LIKE wildcard
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS pgcacheXstore (id int)");
        }

        SchemaManager manager = new SchemaManager(dataSource, "pgcache_store");
        assertFalse(manager.tableExists(),
                "'_' in the table name must be escaped in the metadata lookup");
    }

    @Test
    void uppercaseTableNameMatchesItsOwnCreatedTable() {
        // DDL uses the name unquoted, so PostgreSQL folds it to lowercase;
        // the metadata lookup must fold the same way
        SchemaManager manager = new SchemaManager(dataSource, "UPPER_CASE_CACHE");
        assertFalse(manager.tableExists());

        manager.initializeIfNeeded();
        assertTrue(manager.tableExists(),
                "tableExists must find the table this manager just created");
    }
}
