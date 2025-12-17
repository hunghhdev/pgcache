package io.github.hunghhdev.pgcache.core;

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
public class PgCacheStore implements PgCacheClient, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(PgCacheStore.class);
    private static final String TABLE_NAME = "pgcache_store";
    private static final String CREATE_TABLE_SQL =
        "CREATE UNLOGGED TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
        "  key TEXT PRIMARY KEY, " +
        "  value JSONB NOT NULL, " +
        "  updated_at TIMESTAMP DEFAULT now(), " +
        "  ttl_seconds INT, " +
        "  ttl_policy VARCHAR(10) DEFAULT 'ABSOLUTE', " +
        "  last_accessed TIMESTAMP DEFAULT now()" +
        ")";

    private static final String CREATE_GIN_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS " + TABLE_NAME + "_value_gin_idx " +
        "ON " + TABLE_NAME + " USING GIN (value jsonb_path_ops)";

    private static final String CREATE_TTL_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS " + TABLE_NAME + "_ttl_idx " +
        "ON " + TABLE_NAME + " (updated_at, ttl_seconds) " +
        "WHERE ttl_seconds IS NOT NULL";

    private static final String CREATE_SLIDING_TTL_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS " + TABLE_NAME + "_sliding_ttl_idx " +
        "ON " + TABLE_NAME + " (ttl_policy, last_accessed) " +
        "WHERE ttl_policy = 'SLIDING'";

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final boolean autoCreateTable;
    private final boolean enableBackgroundCleanup;
    private final long cleanupIntervalMinutes;
    private final boolean allowNullValues;
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
        this.allowNullValues = false; // Default to false for backward compatibility

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

            // Create GIN index on value column for JSONB data
            stmt.execute(CREATE_GIN_INDEX_SQL);
            // Create TTL index for efficient expiration queries
            stmt.execute(CREATE_TTL_INDEX_SQL);
            // Create sliding TTL index for efficient sliding TTL queries
            stmt.execute(CREATE_SLIDING_TTL_INDEX_SQL);

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
        return get(key, clazz, true); // Default to refreshing TTL for sliding TTL entries
    }

    @Override
    public <T> void put(String key, T value, Duration ttl) {
        put(key, value, ttl, TTLPolicy.ABSOLUTE); // Default to ABSOLUTE for backward compatibility
    }

    @Override
    public <T> void put(String key, T value) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }
        
        // Handle null values based on allowNullValues setting
        Object valueToStore = value;
        if (value == null) {
            if (!allowNullValues) {
                throw new PgCacheException("Cache value cannot be null (allowNullValues=false)");
            }
            valueToStore = NullValueMarker.getInstance();
        }

        // SQL for upsert without TTL (permanent entry)
        String sql = "INSERT INTO " + TABLE_NAME +
                     " (key, value, updated_at, ttl_seconds) " +
                     "VALUES (?, ?::jsonb, now(), NULL) " +
                     "ON CONFLICT (key) DO UPDATE SET " +
                     "value = EXCLUDED.value, " +
                     "updated_at = EXCLUDED.updated_at, " +
                     "ttl_seconds = EXCLUDED.ttl_seconds";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // Serialize the value to JSON
            String jsonValue = objectMapper.writeValueAsString(valueToStore);

            stmt.setString(1, key);
            stmt.setString(2, jsonValue);

            stmt.executeUpdate();
            invalidateSizeCache(); // Invalidate size cache on modification
        } catch (Exception e) {
            throw new PgCacheException("Failed to put value in cache", e);
        }
    }

    @Override
    public <T> void put(String key, T value, Duration ttl, TTLPolicy policy) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }
        
        // Handle null values based on allowNullValues setting
        Object valueToStore = value;
        if (value == null) {
            if (!allowNullValues) {
                throw new PgCacheException("Cache value cannot be null (allowNullValues=false)");
            }
            valueToStore = NullValueMarker.getInstance();
        }
        
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new PgCacheException("TTL must be positive");
        }
        if (policy == null) {
            throw new PgCacheException("TTL policy cannot be null");
        }

        int ttlSeconds = (int) ttl.getSeconds();

        // SQL for upsert (PostgreSQL specific) with sliding TTL support
        String sql = "INSERT INTO " + TABLE_NAME +
                     " (key, value, updated_at, ttl_seconds, ttl_policy, last_accessed) " +
                     "VALUES (?, ?::jsonb, now(), ?, ?, now()) " +
                     "ON CONFLICT (key) DO UPDATE SET " +
                     "value = EXCLUDED.value, " +
                     "updated_at = EXCLUDED.updated_at, " +
                     "ttl_seconds = EXCLUDED.ttl_seconds, " +
                     "ttl_policy = EXCLUDED.ttl_policy, " +
                     "last_accessed = EXCLUDED.last_accessed";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            // Serialize the value to JSON
            String jsonValue = objectMapper.writeValueAsString(valueToStore);

            stmt.setString(1, key);
            stmt.setString(2, jsonValue);
            stmt.setInt(3, ttlSeconds);
            stmt.setString(4, policy.name());

            stmt.executeUpdate();
            invalidateSizeCache(); // Invalidate size cache on modification
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
            invalidateSizeCache(); // Invalidate size cache on modification
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
            invalidateSizeCache(); // Invalidate size cache on modification
        } catch (SQLException e) {
            throw new PgCacheException("Failed to clear cache", e);
        }
    }

    // Helper method to invalidate size cache
    void invalidateSizeCache() {
        cachedSize = -1;
        lastSizeUpdate = Instant.MIN;
    }

    // Cache for size() method to avoid expensive full table scans
    private volatile int cachedSize = -1;
    private volatile Instant lastSizeUpdate = Instant.MIN;
    private static final Duration SIZE_CACHE_DURATION = Duration.ofSeconds(5);

    @Override
    public int size() {
        // Use cached value if still valid
        if (cachedSize >= 0 && 
            Duration.between(lastSizeUpdate, Instant.now()).compareTo(SIZE_CACHE_DURATION) < 0) {
            logger.trace("Returning cached size: {}", cachedSize);
            return cachedSize;
        }
        
        // Count all non-expired entries (entries with NULL TTL are considered permanent)
        // Note: This query can be slow on large datasets
        String sql = "SELECT COUNT(*) FROM " + TABLE_NAME +
                     " WHERE ttl_seconds IS NULL OR (updated_at + (ttl_seconds * interval '1 second')) > now()";

        try (Connection conn = getValidatedConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                cachedSize = rs.getInt(1);
                lastSizeUpdate = Instant.now();
                logger.debug("Cache size updated: {}", cachedSize);
                return cachedSize;
            }

            return 0;
        } catch (SQLException e) {
            logger.error("Failed to get cache size", e);
            throw new PgCacheException("Failed to get cache size", e);
        }
    }

    /**
     * Removes all expired entries from the cache.
     * This method performs a batch cleanup of all entries that have exceeded their TTL.
     * Entries with NULL TTL (permanent entries) are not affected.
     * 
     * For ABSOLUTE TTL: Expiration is based on updated_at + ttl_seconds
     * For SLIDING TTL: Expiration is based on last_accessed + ttl_seconds
     * 
     * @return the number of expired entries that were removed
     */
    public int cleanupExpired() {
        // Cleanup entries with TTL that have expired, handling both policies correctly
        String sql = "DELETE FROM " + TABLE_NAME +
                     " WHERE ttl_seconds IS NOT NULL AND (" +
                     // ABSOLUTE TTL: expire based on updated_at
                     "  (ttl_policy = 'ABSOLUTE' AND (updated_at + (ttl_seconds * interval '1 second')) <= now()) " +
                     "  OR " +
                     // SLIDING TTL: expire based on last_accessed
                     "  (ttl_policy = 'SLIDING' AND (last_accessed + (ttl_seconds * interval '1 second')) <= now())" +
                     ")";

        try (Connection conn = getValidatedConnection();
             Statement stmt = conn.createStatement()) {

            int deletedCount = stmt.executeUpdate(sql);
            
            if (deletedCount > 0) {
                logger.info("Cleaned up {} expired cache entries", deletedCount);
                invalidateSizeCache(); // Invalidate size cache if entries were removed
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
        private boolean allowNullValues = false;

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
         * Sets whether to allow null values in the cache.
         *
         * @param allowNullValues true to allow null values, false otherwise
         * @return this builder
         * @since 1.3.0
         */
        public Builder allowNullValues(boolean allowNullValues) {
            this.allowNullValues = allowNullValues;
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
                                  enableBackgroundCleanup, cleanupIntervalMinutes, allowNullValues);
        }
    }

    /**
     * Private constructor used by the Builder.
     */
    private PgCacheStore(DataSource dataSource, ObjectMapper objectMapper, boolean autoCreateTable, 
                         boolean enableBackgroundCleanup, long cleanupIntervalMinutes, boolean allowNullValues) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.autoCreateTable = autoCreateTable;
        this.enableBackgroundCleanup = enableBackgroundCleanup;
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;
        this.allowNullValues = allowNullValues;

        if (autoCreateTable) {
            initializeTable();
        }
        
        if (enableBackgroundCleanup) {
            startBackgroundCleanup();
            registerShutdownHook();
        }
    }

    /**
     * Registers a shutdown hook to ensure proper cleanup on JVM shutdown.
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down PgCache cleanup executor via shutdown hook...");
            shutdown();
        }, "pgcache-shutdown-hook"));
    }

    /**
     * Implementation of AutoCloseable for try-with-resources support.
     */
    @Override
    public void close() {
        shutdown();
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

    @Override
    public <T> Optional<T> get(String key, Class<T> clazz, boolean refreshTTL) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }

        String sql = "SELECT value, updated_at, ttl_seconds, ttl_policy, last_accessed FROM " + TABLE_NAME +
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
                Integer ttlSeconds = rs.getObject("ttl_seconds", Integer.class);
                String ttlPolicyStr = rs.getString("ttl_policy");
                
                // Handle null last_accessed for backward compatibility
                Instant lastAccessed = null;
                java.sql.Timestamp lastAccessedTimestamp = rs.getTimestamp("last_accessed");
                if (lastAccessedTimestamp != null) {
                    lastAccessed = lastAccessedTimestamp.toInstant();
                } else {
                    // For backward compatibility, use updated_at if last_accessed is null
                    lastAccessed = updatedAt;
                }

                // Parse TTL policy (default to ABSOLUTE for backward compatibility)
                TTLPolicy ttlPolicy = TTLPolicy.ABSOLUTE;
                if (ttlPolicyStr != null) {
                    try {
                        ttlPolicy = TTLPolicy.valueOf(ttlPolicyStr);
                    } catch (IllegalArgumentException e) {
                        logger.warn("Invalid TTL policy '{}' for key '{}', defaulting to ABSOLUTE", ttlPolicyStr, key);
                    }
                }

                // Check if expired based on TTL policy
                if (ttlSeconds != null) {
                    boolean expired = false;
                    if (ttlPolicy == TTLPolicy.SLIDING) {
                        // For sliding TTL, check against last_accessed time
                        expired = isExpired(lastAccessed, ttlSeconds);
                    } else {
                        // For absolute TTL, check against updated_at time
                        expired = isExpired(updatedAt, ttlSeconds);
                    }

                    if (expired) {
                        // Safely evict expired key
                        try {
                            evict(key);
                        } catch (Exception evictException) {
                            logger.warn("Failed to evict expired key '{}', continuing with empty result", key, evictException);
                        }
                        return Optional.empty();
                    }
                }

                // Update last_accessed timestamp for sliding TTL if refreshTTL is true
                if (refreshTTL && ttlPolicy == TTLPolicy.SLIDING && ttlSeconds != null) {
                    updateLastAccessed(key);
                }

                // Deserialize the value
                Object rawResult = objectMapper.readValue(jsonValue, Object.class);

                // Check if this is a null marker
                if (allowNullValues && rawResult instanceof java.util.Map) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> map = (java.util.Map<String, Object>) rawResult;
                    // NullValueMarker has a "marker" property with value "NULL_MARKER"
                    if (map.size() == 1 && "NULL_MARKER".equals(map.get("marker"))) {
                        // Return the marker so caller can distinguish from cache miss
                        // Caller (e.g., Spring Cache wrapper) should check for this and handle accordingly
                        @SuppressWarnings("unchecked")
                        T marker = (T) NullValueMarker.getInstance();
                        return Optional.of(marker);
                    }
                }

                // Normal deserialization with the requested type
                T result = objectMapper.readValue(jsonValue, clazz);
                return Optional.of(result);

            } catch (Exception e) {
                throw new PgCacheException("Failed to deserialize cached value", e);
            }
        } catch (SQLException e) {
            throw new PgCacheException("Failed to get value from cache", e);
        }
    }

    private void updateLastAccessed(String key) {
        String sql = "UPDATE " + TABLE_NAME + " SET last_accessed = now() WHERE key = ?";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);
            stmt.executeUpdate();

        } catch (SQLException e) {
            logger.warn("Failed to update last_accessed timestamp for key '{}'", key, e);
        }
    }

    @Override
    public Optional<Duration> getRemainingTTL(String key) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }

        String sql = "SELECT updated_at, ttl_seconds, ttl_policy, last_accessed FROM " + TABLE_NAME +
                     " WHERE key = ?";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                Integer ttlSeconds = rs.getObject("ttl_seconds", Integer.class);
                if (ttlSeconds == null) {
                    return Optional.empty(); // No TTL set
                }

                Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
                String ttlPolicyStr = rs.getString("ttl_policy");
                
                // Handle null last_accessed for backward compatibility
                Instant lastAccessed = null;
                java.sql.Timestamp lastAccessedTimestamp = rs.getTimestamp("last_accessed");
                if (lastAccessedTimestamp != null) {
                    lastAccessed = lastAccessedTimestamp.toInstant();
                } else {
                    // For backward compatibility, use updated_at if last_accessed is null
                    lastAccessed = updatedAt;
                }

                // Parse TTL policy
                TTLPolicy ttlPolicy = TTLPolicy.ABSOLUTE;
                if (ttlPolicyStr != null) {
                    try {
                        ttlPolicy = TTLPolicy.valueOf(ttlPolicyStr);
                    } catch (IllegalArgumentException e) {
                        logger.warn("Invalid TTL policy '{}' for key '{}', defaulting to ABSOLUTE", ttlPolicyStr, key);
                    }
                }

                // Calculate remaining TTL based on policy
                Instant expirationTime;
                if (ttlPolicy == TTLPolicy.SLIDING) {
                    expirationTime = lastAccessed.plusSeconds(ttlSeconds);
                } else {
                    expirationTime = updatedAt.plusSeconds(ttlSeconds);
                }

                Duration remaining = Duration.between(Instant.now(), expirationTime);
                return remaining.isNegative() ? Optional.empty() : Optional.of(remaining);

            }
        } catch (SQLException e) {
            throw new PgCacheException("Failed to get remaining TTL from cache", e);
        }
    }

    @Override
    public Optional<TTLPolicy> getTTLPolicy(String key) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }

        String sql = "SELECT ttl_policy FROM " + TABLE_NAME + " WHERE key = ?";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                String ttlPolicyStr = rs.getString("ttl_policy");
                if (ttlPolicyStr == null) {
                    return Optional.of(TTLPolicy.ABSOLUTE); // Default for backward compatibility
                }

                try {
                    return Optional.of(TTLPolicy.valueOf(ttlPolicyStr));
                } catch (IllegalArgumentException e) {
                    logger.warn("Invalid TTL policy '{}' for key '{}', returning ABSOLUTE", ttlPolicyStr, key);
                    return Optional.of(TTLPolicy.ABSOLUTE);
                }
            }
        } catch (SQLException e) {
            throw new PgCacheException("Failed to get TTL policy from cache", e);
        }
    }

    @Override
    public boolean refreshTTL(String key, Duration newTtl) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }
        if (newTtl == null || newTtl.isNegative()) {
            throw new PgCacheException("TTL cannot be null or negative");
        }

        // First try to update existing entry (may include permanent entries)
        String sql = "UPDATE " + TABLE_NAME + 
                     " SET ttl_seconds = ?, ttl_policy = ?, updated_at = CURRENT_TIMESTAMP, last_accessed = CURRENT_TIMESTAMP " +
                     " WHERE key = ?";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, newTtl.getSeconds());
            stmt.setString(2, TTLPolicy.ABSOLUTE.name()); // Default to ABSOLUTE when manually setting TTL
            stmt.setString(3, key);

            int updatedRows = stmt.executeUpdate();
            boolean success = updatedRows > 0;
            
            if (success) {
                logger.debug("Successfully refreshed TTL for key '{}' to {} seconds", key, newTtl.getSeconds());
            } else {
                logger.debug("Failed to refresh TTL for key '{}' - key not found", key);
            }
            
            return success;

        } catch (SQLException e) {
            throw new PgCacheException("Failed to refresh TTL for key: " + key, e);
        }
    }
}
