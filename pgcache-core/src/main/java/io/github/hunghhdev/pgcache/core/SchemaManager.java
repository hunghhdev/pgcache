package io.github.hunghhdev.pgcache.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns table and index initialization for {@link PgCacheStore}.
 * Thread-safe via double-checked locking for one-time initialization.
 *
 * @since 1.7.0
 */
final class SchemaManager {

    private static final Logger logger = LoggerFactory.getLogger(SchemaManager.class);

    private final DataSource dataSource;
    private final String tableName;

    private volatile boolean initialized = false;

    SchemaManager(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = tableName;
    }

    void initializeIfNeeded() {
        if (!initialized) {
            synchronized (this) {
                if (!initialized) {
                    performInitialization();
                    initialized = true;
                }
            }
        }
    }

    boolean tableExists() {
        try (Connection conn = dataSource.getConnection()) {
            return tableExists(conn);
        } catch (SQLException e) {
            throw new PgCacheException("Failed to check if table exists", e);
        }
    }

    private boolean tableExists(Connection conn) throws SQLException {
        String escape = conn.getMetaData().getSearchStringEscape();
        try (ResultSet rs = conn.getMetaData().getTables(
                null, metadataPattern(schemaName(), escape),
                metadataPattern(identifierName(), escape), new String[]{"TABLE"})) {
            return rs.next();
        }
    }

    /**
     * {@code DatabaseMetaData.getTables} treats its arguments as LIKE patterns,
     * so {@code _}/{@code %} must be escaped. DDL uses the name unquoted, which
     * PostgreSQL folds to lowercase — fold the lookup the same way.
     */
    private static String metadataPattern(String identifier, String escape) {
        if (identifier == null) {
            return null;
        }
        String folded = identifier.toLowerCase(java.util.Locale.ROOT);
        if (escape == null || escape.isEmpty()) {
            return folded;
        }
        return folded
                .replace(escape, escape + escape)
                .replace("_", escape + "_")
                .replace("%", escape + "%");
    }

    private void performInitialization() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // DDL is transactional in PostgreSQL: on an auto-commit=false pool
            // the created table would be rolled back on connection return
            if (!conn.getAutoCommit()) {
                conn.setAutoCommit(true);
            }

            boolean tableAlreadyExists = tableExists(conn);

            stmt.execute(createTableSql());
            stmt.execute(createGinIndexSql());
            stmt.execute(createTtlIndexSql());
            stmt.execute(createSlidingTtlIndexSql());

            if (!tableAlreadyExists) {
                logger.info("UNLOGGED table '{}' was created successfully", tableName);
            } else {
                logger.debug("Table '{}' already exists, skipping creation", tableName);
            }
        } catch (SQLException e) {
            logger.error("Failed to initialize cache table", e);
            throw new PgCacheException("Failed to initialize cache table", e);
        }
    }

    private String createTableSql() {
        return "CREATE UNLOGGED TABLE IF NOT EXISTS " + tableName + " (" +
               "  key TEXT PRIMARY KEY, " +
               "  value JSONB NOT NULL, " +
               "  updated_at TIMESTAMP DEFAULT now(), " +
               "  ttl_seconds INT, " +
               "  ttl_policy VARCHAR(10) DEFAULT 'ABSOLUTE', " +
               "  last_accessed TIMESTAMP DEFAULT now()" +
               ")";
    }

    private String createGinIndexSql() {
        return "CREATE INDEX IF NOT EXISTS " + identifierName() + "_value_gin_idx " +
               "ON " + tableName + " USING GIN (value jsonb_path_ops)";
    }

    private String createTtlIndexSql() {
        return "CREATE INDEX IF NOT EXISTS " + identifierName() + "_ttl_idx " +
               "ON " + tableName + " (updated_at, ttl_seconds) " +
               "WHERE ttl_seconds IS NOT NULL";
    }

    private String createSlidingTtlIndexSql() {
        return "CREATE INDEX IF NOT EXISTS " + identifierName() + "_sliding_ttl_idx " +
               "ON " + tableName + " (ttl_policy, last_accessed) " +
               "WHERE ttl_policy = 'SLIDING'";
    }

    private String schemaName() {
        int sep = tableName.indexOf('.');
        return sep >= 0 ? tableName.substring(0, sep) : null;
    }

    private String identifierName() {
        int sep = tableName.indexOf('.');
        return sep >= 0 ? tableName.substring(sep + 1) : tableName;
    }
}
