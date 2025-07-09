package dev.hunghh.pgcache.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PgCacheStore: Implementation of PgCacheClient, directly interacts with PostgreSQL as cache backend.
 */
public class PgCacheStore implements PgCacheClient {
    private static final Logger logger = LoggerFactory.getLogger(PgCacheStore.class);
    private static final String TABLE_NAME = "pgcache_store";
    private static final String CREATE_TABLE_SQL =
        "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
        "  key TEXT PRIMARY KEY, " +
        "  value JSONB NOT NULL, " +
        "  updated_at TIMESTAMP DEFAULT now(), " +
        "  ttl_seconds INT DEFAULT 60" +
        ")";

    private static final String CREATE_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS " + TABLE_NAME + "_key_idx " +
        "ON " + TABLE_NAME + " (key)";

    private static final String CREATE_GIN_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS " + TABLE_NAME + "_value_gin_idx " +
        "ON " + TABLE_NAME + " USING GIN (value jsonb_path_ops)";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final boolean autoCreateTable;

    /**
     * Creates a PgCacheStore with specified dataSource.
     *
     * @param dataSource PostgreSQL DataSource
     * @param autoCreateTable whether to automatically create the cache table if it doesn't exist
     */
    public PgCacheStore(DataSource dataSource, boolean autoCreateTable) {
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper();
        this.autoCreateTable = autoCreateTable;

        if (autoCreateTable) {
            initializeTable();
        }
    }

    /**
     * Creates a PgCacheStore with auto table creation enabled.
     *
     * @param dataSource PostgreSQL DataSource
     */
    public PgCacheStore(DataSource dataSource) {
        this(dataSource, true);
    }

    /**
     * Initializes the cache table in the database if it doesn't exist.
     */
    private void initializeTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Check if table already exists
            boolean tableExists = false;
            try (ResultSet rs = conn.getMetaData().getTables(null, null, TABLE_NAME, new String[] {"TABLE"})) {
                tableExists = rs.next();
            }

            // Create table if it doesn't exist
            stmt.execute(CREATE_TABLE_SQL);

            // Create index on updated_at and ttl_seconds for expiry checking
            stmt.execute(CREATE_INDEX_SQL);
            // Create GIN index on value column for JSONB data
            stmt.execute(CREATE_GIN_INDEX_SQL);

            // Log table creation status
            if (!tableExists) {
                logger.info("Table '{}' was created successfully", TABLE_NAME);
            } else {
                logger.debug("Table '{}' already exists, skipping creation", TABLE_NAME);
            }
        } catch (SQLException e) {
            logger.error("Failed to initialize cache table", e);
            throw new PgCacheException("Failed to initialize cache table", e);
        }
    }

    /**
     * Checks if the table exists in the database.
     *
     * @return true if the table exists, false otherwise
     */
    public boolean tableExists() {
        try (Connection conn = dataSource.getConnection()) {
            ResultSet tables = conn.getMetaData().getTables(
                null, null, TABLE_NAME, new String[] {"TABLE"});
            return tables.next();
        } catch (SQLException e) {
            throw new PgCacheException("Failed to check if table exists", e);
        }
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> clazz) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }

        String sql = "SELECT value, updated_at, ttl_seconds FROM " + TABLE_NAME +
                     " WHERE key = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                // Get the values
                String jsonValue = rs.getString("value");
                Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
                int ttlSeconds = rs.getInt("ttl_seconds");

                // Check if expired
                if (isExpired(updatedAt, ttlSeconds)) {
                    evict(key);
                    return Optional.empty();
                }

                // Deserialize and return
                T value = objectMapper.readValue(jsonValue, clazz);
                return Optional.of(value);
            }
        } catch (Exception e) {
            throw new PgCacheException("Failed to get value from cache", e);
        }
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }
        if (value == null) {
            throw new PgCacheException("Cache value cannot be null");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new PgCacheException("TTL must be positive");
        }

        int ttlSeconds = (int) ttl.getSeconds();

        // SQL for upsert (PostgreSQL specific)
        String sql = "INSERT INTO " + TABLE_NAME +
                     " (key, value, updated_at, ttl_seconds) " +
                     "VALUES (?, ?::jsonb, now(), ?) " +
                     "ON CONFLICT (key) DO UPDATE SET " +
                     "value = EXCLUDED.value, " +
                     "updated_at = EXCLUDED.updated_at, " +
                     "ttl_seconds = EXCLUDED.ttl_seconds";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // Serialize the value to JSON
            String jsonValue = objectMapper.writeValueAsString(value);

            stmt.setString(1, key);
            stmt.setString(2, jsonValue);
            stmt.setInt(3, ttlSeconds);

            stmt.executeUpdate();
        } catch (Exception e) {
            throw new PgCacheException("Failed to put value in cache", e);
        }
    }

    @Override
    public void evict(String key) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }

        String sql = "DELETE FROM " + TABLE_NAME + " WHERE key = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new PgCacheException("Failed to evict key from cache", e);
        }
    }

    @Override
    public void clear() {
        String sql = "DELETE FROM " + TABLE_NAME;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw new PgCacheException("Failed to clear cache", e);
        }
    }

    @Override
    public int size() {
        String sql = "SELECT COUNT(*) FROM " + TABLE_NAME +
                     " WHERE (updated_at + (ttl_seconds * interval '1 second')) > now()";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                return rs.getInt(1);
            }

            return 0;
        } catch (SQLException e) {
            throw new PgCacheException("Failed to get cache size", e);
        }
    }

    /**
     * Checks if a cache entry is expired.
     *
     * @param updatedAt the timestamp when the entry was last updated
     * @param ttlSeconds the TTL in seconds
     * @return true if the entry is expired, false otherwise
     */
    private boolean isExpired(Instant updatedAt, int ttlSeconds) {
        return Instant.now().isAfter(updatedAt.plusSeconds(ttlSeconds));
    }

    /**
     * Creates a builder for PgCacheStore.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for PgCacheStore.
     */
    public static class Builder {
        private DataSource dataSource;
        private ObjectMapper objectMapper;
        private boolean autoCreateTable = true;

        private Builder() {
            this.objectMapper = new ObjectMapper();
        }

        /**
         * Sets the data source.
         *
         * @param dataSource the PostgreSQL data source
         * @return this builder
         */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /**
         * Sets the object mapper for JSON serialization/deserialization.
         *
         * @param objectMapper the object mapper
         * @return this builder
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * Sets whether to automatically create the cache table if it doesn't exist.
         *
         * @param autoCreateTable true to auto-create table, false otherwise
         * @return this builder
         */
        public Builder autoCreateTable(boolean autoCreateTable) {
            this.autoCreateTable = autoCreateTable;
            return this;
        }

        /**
         * Builds a new PgCacheStore instance.
         *
         * @return a new PgCacheStore instance
         * @throws IllegalStateException if dataSource is not set
         */
        public PgCacheStore build() {
            if (dataSource == null) {
                throw new IllegalStateException("DataSource must be set");
            }
            if (objectMapper == null) {
                objectMapper = new ObjectMapper();
            }
            return new PgCacheStore(dataSource, objectMapper, autoCreateTable);
        }
    }

    /**
     * Private constructor used by the Builder.
     */
    private PgCacheStore(DataSource dataSource, ObjectMapper objectMapper, boolean autoCreateTable) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.autoCreateTable = autoCreateTable;

        if (autoCreateTable) {
            initializeTable();
        }
    }
}
