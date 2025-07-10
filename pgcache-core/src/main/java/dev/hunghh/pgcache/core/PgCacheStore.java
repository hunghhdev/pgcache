package dev.hunghh.pgcache.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PgCacheStore: Implementation of PgCacheClient, directly interacts with PostgreSQL as cache backend.
 * 
 * <p>This implementation is thread-safe and can be used concurrently from multiple threads.
 * It uses double-checked locking for efficient table initialization and proper exception
 * handling to prevent resource leaks.</p>
 * 
 * <p><strong>Thread Safety:</strong></p>
 * <ul>
 *   <li>Table initialization uses double-checked locking pattern</li>
 *   <li>Jackson ObjectMapper is thread-safe for read operations</li>
 *   <li>DataSource connections are obtained per operation</li>
 *   <li>All cache operations are safe for concurrent access</li>
 * </ul>
 */
public class PgCacheStore implements PgCacheClient {
    private static final Logger logger = LoggerFactory.getLogger(PgCacheStore.class);
    private static final String TABLE_NAME = "pgcache_store";
    private static final String CREATE_TABLE_SQL =
        "CREATE UNLOGGED TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
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

    private static final String CREATE_TTL_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS " + TABLE_NAME + "_ttl_idx " +
        "ON " + TABLE_NAME + " (updated_at, ttl_seconds)";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final boolean autoCreateTable;
    private final boolean enableBackgroundCleanup;
    private final long cleanupIntervalMinutes;
    private volatile ScheduledExecutorService cleanupExecutor;
    
    // Thread-safe initialization flag using double-checked locking pattern
    private volatile boolean tableInitialized = false;

    // Connection retry configuration
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 100;

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
        this.enableBackgroundCleanup = false;
        this.cleanupIntervalMinutes = 5;

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
     * Uses double-checked locking pattern for thread safety.
     */
    private void initializeTable() {
        // First check without synchronization for performance
        if (!tableInitialized) {
            synchronized (this) {
                // Second check with synchronization to ensure only one thread initializes
                if (!tableInitialized) {
                    performTableInitialization();
                    tableInitialized = true;
                }
            }
        }
    }

    /**
     * Performs the actual table initialization logic.
     * This method is called only once and only from synchronized context.
     */
    private void performTableInitialization() {
        try (Connection conn = getValidatedConnection();
             Statement stmt = conn.createStatement()) {

            // Check if table already exists
            boolean tableExists = false;
            try (ResultSet rs = conn.getMetaData().getTables(null, null, TABLE_NAME, new String[] {"TABLE"})) {
                tableExists = rs.next();
            }

            // Create table with dynamic UNLOGGED option
            stmt.execute(CREATE_TABLE_SQL);

            // Create index on updated_at and ttl_seconds for expiry checking
            stmt.execute(CREATE_INDEX_SQL);
            // Create GIN index on value column for JSONB data
            stmt.execute(CREATE_GIN_INDEX_SQL);
            // Create TTL index for efficient expiration queries
            stmt.execute(CREATE_TTL_INDEX_SQL);

            // Log table creation status
            if (!tableExists) {
                logger.info("UNLOGGED table '{}' was created successfully", TABLE_NAME);
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
        try (Connection conn = getValidatedConnection()) {
            try (ResultSet tables = conn.getMetaData().getTables(
                    null, null, TABLE_NAME, new String[] {"TABLE"})) {
                return tables.next();
            }
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

        try (Connection conn = getValidatedConnection();
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
                    // Safely evict expired key - don't let eviction failures affect the get operation
                    try {
                        evict(key);
                    } catch (Exception evictException) {
                        logger.warn("Failed to evict expired key '{}', continuing with empty result", key, evictException);
                    }
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

        try (Connection conn = getValidatedConnection();
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

        try (Connection conn = getValidatedConnection();
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

        try (Connection conn = getValidatedConnection();
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

        try (Connection conn = getValidatedConnection();
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
     * Removes all expired entries from the cache.
     * This method performs a batch cleanup of all entries that have exceeded their TTL.
     * 
     * @return the number of expired entries that were removed
     */
    public int cleanupExpired() {
        String sql = "DELETE FROM " + TABLE_NAME +
                     " WHERE (updated_at + (ttl_seconds * interval '1 second')) <= now()";

        try (Connection conn = getValidatedConnection();
             Statement stmt = conn.createStatement()) {

            int deletedCount = stmt.executeUpdate(sql);
            
            if (deletedCount > 0) {
                logger.info("Cleaned up {} expired cache entries", deletedCount);
            } else {
                logger.debug("No expired cache entries found during cleanup");
            }
            
            return deletedCount;
        } catch (SQLException e) {
            logger.error("Failed to cleanup expired cache entries", e);
            throw new PgCacheException("Failed to cleanup expired cache entries", e);
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
        private boolean enableBackgroundCleanup = false;
        private long cleanupIntervalMinutes = 5;

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
         * Enables background cleanup of expired entries.
         *
         * @param enableBackgroundCleanup true to enable background cleanup
         * @return this builder
         */
        public Builder enableBackgroundCleanup(boolean enableBackgroundCleanup) {
            this.enableBackgroundCleanup = enableBackgroundCleanup;
            return this;
        }

        /**
         * Sets the interval for background cleanup.
         *
         * @param cleanupIntervalMinutes cleanup interval in minutes
         * @return this builder
         */
        public Builder cleanupIntervalMinutes(long cleanupIntervalMinutes) {
            this.cleanupIntervalMinutes = cleanupIntervalMinutes;
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
            return new PgCacheStore(dataSource, objectMapper, autoCreateTable, 
                                  enableBackgroundCleanup, cleanupIntervalMinutes);
        }
    }

    /**
     * Private constructor used by the Builder.
     */
    private PgCacheStore(DataSource dataSource, ObjectMapper objectMapper, boolean autoCreateTable, 
                         boolean enableBackgroundCleanup, long cleanupIntervalMinutes) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.autoCreateTable = autoCreateTable;
        this.enableBackgroundCleanup = enableBackgroundCleanup;
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;

        if (autoCreateTable) {
            initializeTable();
        }
        
        if (enableBackgroundCleanup) {
            startBackgroundCleanup();
        }
    }

    /**
     * Starts the background cleanup task if enabled.
     */
    private void startBackgroundCleanup() {
        if (cleanupExecutor == null || cleanupExecutor.isShutdown()) {
            cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pgcache-cleanup");
                t.setDaemon(true);
                return t;
            });
            
            cleanupExecutor.scheduleWithFixedDelay(() -> {
                try {
                    cleanupExpired();
                } catch (Exception e) {
                    logger.warn("Background cleanup failed", e);
                }
            }, cleanupIntervalMinutes, cleanupIntervalMinutes, TimeUnit.MINUTES);
            
            logger.info("Started background cleanup with interval: {} minutes", cleanupIntervalMinutes);
        }
    }

    /**
     * Stops the background cleanup task.
     */
    public void shutdown() {
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cleanupExecutor.shutdownNow();
            }
            logger.info("Background cleanup shutdown completed");
        }
    }

    /**
     * Gets a connection from the DataSource with retry logic for transient failures.
     * 
     * @return a database connection
     * @throws SQLException if unable to obtain a connection after retries
     */
    private Connection getValidatedConnection() throws SQLException {
        SQLException lastException = null;
        
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return dataSource.getConnection();
                
            } catch (SQLException e) {
                lastException = e;
                logger.warn("Connection attempt {} failed: {}", attempt, e.getMessage());
                
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Connection retry interrupted", ie);
                    }
                }
            }
        }
        
        throw new SQLException("Failed to obtain connection after " + MAX_RETRY_ATTEMPTS + " attempts", lastException);
    }


}
