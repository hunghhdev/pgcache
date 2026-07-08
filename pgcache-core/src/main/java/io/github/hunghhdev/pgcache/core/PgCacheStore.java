package io.github.hunghhdev.pgcache.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.sql.DataSource;

import com.fasterxml.jackson.core.JsonProcessingException;
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
    private static final String DEFAULT_TABLE_NAME = "pgcache_store";

    /**
     * SQL WHERE clause for checking non-expired entries.
     * Handles: permanent entries (NULL ttl), ABSOLUTE TTL, and SLIDING TTL.
     */
    private static final String NOT_EXPIRED_WHERE_CLAUSE = notExpiredClause("");

    /**
     * Builds the non-expired predicate with an optional column prefix
     * (e.g. {@code "t."}) for contexts where bare column names would be
     * ambiguous, such as ON CONFLICT DO UPDATE.
     */
    private static String notExpiredClause(String col) {
        return "(" + col + "ttl_seconds IS NULL" +
            " OR ((" + col + "ttl_policy = 'ABSOLUTE' OR " + col + "ttl_policy IS NULL)" +
            " AND (" + col + "updated_at + (" + col + "ttl_seconds * interval '1 second')) > now())" +
            " OR (" + col + "ttl_policy = 'SLIDING'" +
            " AND ((COALESCE(" + col + "last_accessed, " + col + "updated_at)) + (" + col + "ttl_seconds * interval '1 second')) > now()))";
    }

    private static final String SQL_IDENTIFIER_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]*$";

    /**
     * SQL WHERE clause for checking expired entries (inverse of NOT_EXPIRED).
     * Used for cleanup operations.
     */
    private static final String EXPIRED_WHERE_CLAUSE =
        "(ttl_seconds IS NOT NULL AND (" +
        "((ttl_policy = 'ABSOLUTE' OR ttl_policy IS NULL) AND (updated_at + (ttl_seconds * interval '1 second')) <= now()) " +
        "OR (ttl_policy = 'SLIDING' AND ((COALESCE(last_accessed, updated_at)) + (ttl_seconds * interval '1 second')) <= now())))";

    // Connection retry configuration
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 100;

    // Background cleanup defaults (constants live in BackgroundCleanupScheduler)
    private static final long DEFAULT_CLEANUP_INTERVAL_MINUTES =
            BackgroundCleanupScheduler.DEFAULT_INTERVAL_MINUTES;

    // Size-cache TTL
    private static final Duration SIZE_CACHE_DURATION = Duration.ofSeconds(5);

    // Core dependencies
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String tableName;
    private final boolean allowNullValues;
    private final Executor asyncExecutor;
    private final CacheEventDispatcher eventDispatcher;
    private final List<CacheEventListener> eventListeners;
    private final SchemaManager schemaManager;

    // Background cleanup
    private final BackgroundCleanupScheduler cleanupScheduler;

    // Statistics counters
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong putCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);

    // Cache for size() method to avoid expensive full table scans
    private final AtomicReference<SizeSnapshot> sizeCache = new AtomicReference<>();

    /** Size + timestamp published atomically so an invalidation cannot be lost. */
    private static final class SizeSnapshot {
        final int size;
        final Instant takenAt;

        SizeSnapshot(int size, Instant takenAt) {
            this.size = size;
            this.takenAt = takenAt;
        }
    }

    /**
     * Creates a PgCacheStore with specified dataSource.
     *
     * @param dataSource PostgreSQL DataSource
     * @param autoCreateTable whether to automatically create the cache table if it doesn't exist
     * @deprecated since 1.7.0, use {@link #builder()} -- the canonical configuration entry point.
     *     This constructor exposes only a subset of options (no custom table name, no event listeners,
     *     no background cleanup). Removed in 2.0.0.
     */
    @Deprecated
    public PgCacheStore(DataSource dataSource, boolean autoCreateTable) {
        this.dataSource = dataSource;
        this.objectMapper = new ObjectMapper();
        this.tableName = DEFAULT_TABLE_NAME;
        this.allowNullValues = false;
        this.eventListeners = new ArrayList<>();
        this.asyncExecutor = ForkJoinPool.commonPool();
        this.eventDispatcher = new CacheEventDispatcher(this.eventListeners);
        this.schemaManager = new SchemaManager(dataSource, this.tableName);
        this.cleanupScheduler = null;  // public ctor doesn't enable background cleanup

        if (autoCreateTable) {
            schemaManager.initializeIfNeeded();
        }
    }

    /**
     * Creates a PgCacheStore with auto table creation enabled.
     *
     * @param dataSource PostgreSQL DataSource
     * @deprecated since 1.7.0, use {@link #builder()}. Removed in 2.0.0.
     */
    @Deprecated
    public PgCacheStore(DataSource dataSource) {
        this(dataSource, true);
    }

    /**
     * Checks if the table exists in the database.
     *
     * @return true if the table exists, false otherwise
     */
    public boolean tableExists() {
        return schemaManager.tableExists();
    }

    /**
     * Returns the name of the table backing this store.
     *
     * @return the (possibly schema-qualified) table name
     * @since 1.8.0
     */
    public String getTableName() {
        return tableName;
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
        validateKey(key);
        
        Object valueToStore = normalizeValue(value);

        // SQL for upsert without TTL (permanent entry)
        String sql = "INSERT INTO " + tableName +
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
            putCount.incrementAndGet();
            invalidateSizeCache();
            fireOnPut(key, value);
        } catch (SQLException | JsonProcessingException e) {
            throw new PgCacheException("Failed to put value in cache", e);
        }
    }

    @Override
    public <T> void put(String key, T value, Duration ttl, TTLPolicy policy) {
        validateKey(key);
        
        Object valueToStore = normalizeValue(value);
        
        if (policy == null) {
            throw new PgCacheException("TTL policy cannot be null");
        }

        int ttlSeconds = normalizeTtlSeconds(ttl);

        // SQL for upsert (PostgreSQL specific) with sliding TTL support
        String sql = "INSERT INTO " + tableName +
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
            putCount.incrementAndGet();
            invalidateSizeCache();
            fireOnPut(key, value);
        } catch (SQLException | JsonProcessingException e) {
            throw new PgCacheException("Failed to put value in cache", e);
        }
    }

    @Override
    public void evict(String key) {
        validateKey(key);

        String sql = "DELETE FROM " + tableName + " WHERE key = ?";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                evictionCount.incrementAndGet();
                invalidateSizeCache();
                fireOnEvict(key);
            }
        } catch (SQLException e) {
            throw new PgCacheException("Failed to evict key from cache", e);
        }
    }

    @Override
    public void clear() {
        String sql = "DELETE FROM " + tableName;

        try (Connection conn = getValidatedConnection();
             Statement stmt = conn.createStatement()) {

            int deleted = stmt.executeUpdate(sql);
            if (deleted > 0) {
                evictionCount.addAndGet(deleted);
            }
            invalidateSizeCache();
            fireOnClear();
        } catch (SQLException e) {
            throw new PgCacheException("Failed to clear cache", e);
        }
    }

    void invalidateSizeCache() {
        sizeCache.set(null);
    }

    private void fireOnPut(String key, Object value) {
        eventDispatcher.fireOnPut(key, value);
    }

    private void fireOnEvict(String key) {
        eventDispatcher.fireOnEvict(key);
    }

    private void fireOnClear() {
        eventDispatcher.fireOnClear();
    }

    @Override
    public int size() {
        SizeSnapshot snapshot = sizeCache.get();
        if (snapshot != null &&
            Duration.between(snapshot.takenAt, Instant.now()).compareTo(SIZE_CACHE_DURATION) < 0) {
            logger.trace("Returning cached size: {}", snapshot.size);
            return snapshot.size;
        }

        String sql = "SELECT COUNT(*) FROM " + tableName + " WHERE " + NOT_EXPIRED_WHERE_CLAUSE;

        try (Connection conn = getValidatedConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            if (rs.next()) {
                int size = rs.getInt(1);
                // CAS against the snapshot we started from: if a concurrent write
                // invalidated the cache while COUNT ran, do not overwrite that
                // invalidation with a stale count
                sizeCache.compareAndSet(snapshot, new SizeSnapshot(size, Instant.now()));
                logger.debug("Cache size updated: {}", size);
                return size;
            }

            return 0;
        } catch (SQLException e) {
            logger.error("Failed to get cache size", e);
            throw new PgCacheException("Failed to get cache size", e);
        }
    }

    @Override
    public int size(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new PgCacheException("Pattern cannot be null or empty");
        }

        String sql = "SELECT COUNT(*) FROM " + tableName +
                     " WHERE key LIKE ? ESCAPE '\\' AND " + NOT_EXPIRED_WHERE_CLAUSE;

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, pattern);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new PgCacheException("Failed to count entries by pattern: " + pattern, e);
        }
    }

    @Override
    public boolean containsKey(String key) {
        validateKey(key);

        String sql = "SELECT 1 FROM " + tableName +
                     " WHERE key = ? AND " + NOT_EXPIRED_WHERE_CLAUSE + " LIMIT 1";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new PgCacheException("Failed to check if key exists in cache", e);
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
        String sql = "DELETE FROM " + tableName + " WHERE " + EXPIRED_WHERE_CLAUSE;

        try (Connection conn = getValidatedConnection();
             Statement stmt = conn.createStatement()) {

            int deletedCount = stmt.executeUpdate(sql);
            
            if (deletedCount > 0) {
                evictionCount.addAndGet(deletedCount);
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
        private Duration cleanupInterval = Duration.ofMinutes(DEFAULT_CLEANUP_INTERVAL_MINUTES);
        private boolean allowNullValues = false;
        private String tableName = DEFAULT_TABLE_NAME;
        private List<CacheEventListener> eventListeners = new ArrayList<>();
        private Executor asyncExecutor;

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
         * @param cleanupIntervalMinutes cleanup interval in minutes, must be positive
         * @return this builder
         */
        public Builder cleanupIntervalMinutes(long cleanupIntervalMinutes) {
            return cleanupInterval(Duration.ofMinutes(cleanupIntervalMinutes));
        }

        /**
         * Sets the interval for background cleanup. Supports sub-minute intervals.
         *
         * @param cleanupInterval cleanup interval, must be positive
         * @return this builder
         * @since 1.7.1
         */
        public Builder cleanupInterval(Duration cleanupInterval) {
            if (cleanupInterval == null || cleanupInterval.isZero() || cleanupInterval.isNegative()) {
                throw new IllegalArgumentException("cleanupInterval must be positive, got: " + cleanupInterval);
            }
            this.cleanupInterval = cleanupInterval;
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

        public Builder tableName(String tableName) {
            if (tableName == null || tableName.trim().isEmpty()) {
                throw new IllegalArgumentException("tableName cannot be null or empty");
            }
            String trimmed = tableName.trim();
            if (!isValidTableName(trimmed)) {
                throw new IllegalArgumentException("tableName must be a valid SQL identifier or schema-qualified identifier (letters, digits, underscores only)");
            }
            this.tableName = trimmed;
            return this;
        }

        public Builder addEventListener(CacheEventListener listener) {
            if (listener != null) {
                this.eventListeners.add(listener);
            }
            return this;
        }

        public Builder asyncExecutor(Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
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
            return new PgCacheStore(dataSource, objectMapper, tableName, autoCreateTable,
                                  enableBackgroundCleanup, cleanupInterval, allowNullValues,
                                  eventListeners, asyncExecutor);
        }
    }

    private PgCacheStore(DataSource dataSource, ObjectMapper objectMapper, String tableName, boolean autoCreateTable,
                         boolean enableBackgroundCleanup, Duration cleanupInterval, boolean allowNullValues,
                         List<CacheEventListener> eventListeners, Executor asyncExecutor) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        if (tableName == null || tableName.trim().isEmpty()) {
            this.tableName = DEFAULT_TABLE_NAME;
        } else {
            String trimmed = tableName.trim();
            if (!isValidTableName(trimmed)) {
                throw new IllegalArgumentException(
                    "tableName must be a valid SQL identifier or schema-qualified identifier (letters, digits, underscores only)");
            }
            this.tableName = trimmed;
        }
        this.allowNullValues = allowNullValues;
        this.eventListeners = eventListeners != null ? new ArrayList<>(eventListeners) : new ArrayList<>();
        this.asyncExecutor = asyncExecutor != null ? asyncExecutor : ForkJoinPool.commonPool();
        this.eventDispatcher = new CacheEventDispatcher(this.eventListeners);
        this.schemaManager = new SchemaManager(dataSource, this.tableName);

        if (autoCreateTable) {
            schemaManager.initializeIfNeeded();
        }

        if (enableBackgroundCleanup) {
            this.cleanupScheduler = new BackgroundCleanupScheduler(cleanupInterval, this::cleanupExpired);
            cleanupScheduler.start();
            cleanupScheduler.registerShutdownHook();
        } else {
            this.cleanupScheduler = null;
        }
    }

    /**
     * Implementation of AutoCloseable for try-with-resources support.
     */
    @Override
    public void close() {
        shutdown();
    }

    /**
     * Stops the background cleanup task.
     */
    public void shutdown() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
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
                Connection conn = dataSource.getConnection();
                // Pools configured with auto-commit=false would roll back our
                // statements on connection return, silently losing writes.
                // Every operation here is a single self-contained statement,
                // so autocommit is always the correct mode.
                if (!conn.getAutoCommit()) {
                    conn.setAutoCommit(true);
                }
                return conn;

            } catch (SQLException e) {
                if (!isTransientConnectionFailure(e)) {
                    // Auth failures, bad URLs etc. cannot succeed on retry —
                    // retrying only adds latency on a permanently broken pool
                    throw e;
                }
                lastException = e;
                logger.warn("Connection attempt {} failed: {}", attempt, e.getMessage());

                if (attempt < MAX_RETRY_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS << (attempt - 1)); // exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("Connection retry interrupted", ie);
                    }
                }
            }
        }
        
        throw new SQLException("Failed to obtain connection after " + MAX_RETRY_ATTEMPTS + " attempts", lastException);
    }

    /**
     * Only SQLState class 08 (connection exception) and transient exceptions
     * are worth retrying; anything else (authentication, syntax, config)
     * fails identically on every attempt.
     */
    private static boolean isTransientConnectionFailure(SQLException e) {
        if (e instanceof java.sql.SQLTransientException) {
            return true;
        }
        String sqlState = e.getSQLState();
        return sqlState != null && sqlState.startsWith("08");
    }

    @Override
    public <T> Optional<T> get(String key, Class<T> clazz, boolean refreshTTL) {
        validateKey(key);

        // Single statement: read the live row and, in the same snapshot, slide
        // last_accessed for SLIDING entries when refreshTTL is requested.
        // Keeping this atomic avoids checking out a second pooled connection
        // and cannot re-arm a row that has already expired.
        String sql = "WITH hit AS (" +
                     "SELECT key, value, ttl_seconds, ttl_policy FROM " + tableName +
                     " WHERE key = ? AND " + NOT_EXPIRED_WHERE_CLAUSE +
                     "), refresh AS (" +
                     "UPDATE " + tableName + " t SET last_accessed = now() FROM hit" +
                     " WHERE CAST(? AS boolean) AND t.key = hit.key" +
                     " AND hit.ttl_policy = 'SLIDING' AND hit.ttl_seconds IS NOT NULL" +
                     ") SELECT value FROM hit";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);
            stmt.setBoolean(2, refreshTTL);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    missCount.incrementAndGet();
                    return Optional.empty();
                }

                String jsonValue = rs.getString("value");

                // Check if this is a null marker
                if (allowNullValues) {
                    Object rawResult = objectMapper.readValue(jsonValue, Object.class);
                    if (NullValueMarker.isMarker(rawResult)) {
                        @SuppressWarnings("unchecked")
                        T marker = (T) NullValueMarker.getInstance();
                        hitCount.incrementAndGet();
                        return Optional.of(marker);
                    }
                }

                // Normal deserialization with the requested type
                T result = objectMapper.readValue(jsonValue, clazz);
                hitCount.incrementAndGet();
                return Optional.of(result);

            } catch (JsonProcessingException e) {
                throw new PgCacheException("Failed to deserialize cached value", e);
            }
        } catch (SQLException e) {
            throw new PgCacheException("Failed to get value from cache", e);
        }
    }

    @Override
    public Optional<Duration> getRemainingTTL(String key) {
        validateKey(key);

        String sql = "SELECT updated_at, ttl_seconds, ttl_policy, last_accessed FROM " + tableName +
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
                    return Optional.empty();
                }

                java.sql.Timestamp updatedAtTimestamp = rs.getTimestamp("updated_at");
                Instant updatedAt = updatedAtTimestamp != null ? updatedAtTimestamp.toInstant() : Instant.now();
                String ttlPolicyStr = rs.getString("ttl_policy");

                java.sql.Timestamp lastAccessedTimestamp = rs.getTimestamp("last_accessed");
                Instant lastAccessed = lastAccessedTimestamp != null ? lastAccessedTimestamp.toInstant() : updatedAt;

                TTLPolicy ttlPolicy = TTLPolicy.parse(ttlPolicyStr).orElseGet(() -> {
                    if (ttlPolicyStr != null) {
                        logger.warn("Invalid TTL policy '{}' for key '{}', defaulting to ABSOLUTE", ttlPolicyStr, key);
                    }
                    return TTLPolicy.ABSOLUTE;
                });

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
        validateKey(key);

        String sql = "SELECT ttl_policy FROM " + tableName + " WHERE key = ?";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, key);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }

                String ttlPolicyStr = rs.getString("ttl_policy");
                return Optional.of(TTLPolicy.parse(ttlPolicyStr).orElseGet(() -> {
                    if (ttlPolicyStr != null) {
                        logger.warn("Invalid TTL policy '{}' for key '{}', returning ABSOLUTE", ttlPolicyStr, key);
                    }
                    return TTLPolicy.ABSOLUTE;
                }));
            }
        } catch (SQLException e) {
            throw new PgCacheException("Failed to get TTL policy from cache", e);
        }
    }

    @Override
    public boolean refreshTTL(String key, Duration newTtl) {
        validateKey(key);
        int ttlSeconds = normalizeTtlSeconds(newTtl);

        // Update live entries only (permanent or not yet expired) — a logically
        // expired row awaiting cleanup must not be resurrected with its stale value
        String sql = "UPDATE " + tableName +
                     " SET ttl_seconds = ?, updated_at = CURRENT_TIMESTAMP, last_accessed = CURRENT_TIMESTAMP " +
                     " WHERE key = ? AND " + NOT_EXPIRED_WHERE_CLAUSE;

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setInt(1, ttlSeconds);
            stmt.setString(2, key);

            int updatedRows = stmt.executeUpdate();
            boolean success = updatedRows > 0;
            
            if (success) {
                logger.debug("Successfully refreshed TTL for key '{}' to {} seconds", key, ttlSeconds);
            } else {
                logger.debug("Failed to refresh TTL for key '{}' - key not found or expired", key);
            }
            
            return success;

        } catch (SQLException e) {
            throw new PgCacheException("Failed to refresh TTL for key: " + key, e);
        }
    }

    @Override
    public <T> Optional<Object> putIfAbsent(String key, T value, Duration ttl, TTLPolicy policy) {
        validateKey(key);

        Object valueToStore = normalizeValue(value);

        if (policy == null) {
            throw new PgCacheException("TTL policy cannot be null");
        }

        int ttlSeconds = normalizeTtlSeconds(ttl);

        return doPutIfAbsent(key, valueToStore, value, ttlSeconds, policy);
    }

    @Override
    public <T> Optional<Object> putIfAbsent(String key, T value) {
        validateKey(key);

        Object valueToStore = normalizeValue(value);

        return doPutIfAbsent(key, valueToStore, value, null, null);
    }

    /**
     * Single-statement, single-connection insert-if-absent.
     *
     * <p>The UPSERT overwrites only logically expired rows (an expired row is
     * equivalent to an absent key), and the same statement returns the existing
     * live value on conflict — no separate DELETE/SELECT that could race or
     * require a second pooled connection.</p>
     */
    private Optional<Object> doPutIfAbsent(String key, Object valueToStore, Object originalValue,
                                           Integer ttlSeconds, TTLPolicy policy) {
        String sql = "WITH attempt AS (" +
                     "INSERT INTO " + tableName + " AS t (key, value, updated_at, ttl_seconds, ttl_policy, last_accessed) " +
                     "VALUES (?, ?::jsonb, now(), ?, ?, now()) " +
                     "ON CONFLICT (key) DO UPDATE SET " +
                     "value = EXCLUDED.value, updated_at = EXCLUDED.updated_at, " +
                     "ttl_seconds = EXCLUDED.ttl_seconds, ttl_policy = EXCLUDED.ttl_policy, " +
                     "last_accessed = EXCLUDED.last_accessed " +
                     "WHERE NOT " + notExpiredClause("t.") + " " +
                     "RETURNING t.key" +
                     ") SELECT EXISTS(SELECT 1 FROM attempt) AS inserted, " +
                     "(SELECT s.value FROM " + tableName + " s WHERE s.key = ? " +
                     "AND NOT EXISTS (SELECT 1 FROM attempt)) AS existing_value";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            String jsonValue = objectMapper.writeValueAsString(valueToStore);

            stmt.setString(1, key);
            stmt.setString(2, jsonValue);
            if (ttlSeconds != null) {
                stmt.setInt(3, ttlSeconds);
            } else {
                stmt.setNull(3, java.sql.Types.INTEGER);
            }
            stmt.setString(4, policy != null ? policy.name() : TTLPolicy.ABSOLUTE.name());
            stmt.setString(5, key);

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new PgCacheException("putIfAbsent returned no result row for key: " + key);
                }
                boolean inserted = rs.getBoolean("inserted");

                if (inserted) {
                    putCount.incrementAndGet();
                    invalidateSizeCache();
                    fireOnPut(key, originalValue);
                    return Optional.empty();
                }

                String existingJson = rs.getString("existing_value");
                if (existingJson == null) {
                    // Conflicting row was committed concurrently after our snapshot:
                    // visible to the conflict arbiter but not to the subselect.
                    // Re-read on the same connection (new snapshot).
                    try (PreparedStatement reread = conn.prepareStatement(
                            "SELECT value FROM " + tableName + " WHERE key = ? AND " + NOT_EXPIRED_WHERE_CLAUSE)) {
                        reread.setString(1, key);
                        try (ResultSet rrs = reread.executeQuery()) {
                            if (rrs.next()) {
                                existingJson = rrs.getString(1);
                            }
                        }
                    }
                }
                if (existingJson == null) {
                    // The conflicting row expired/vanished immediately after the
                    // attempt — the key is absent now, but our value was not
                    // stored. Report the (stale) absence honestly as empty per
                    // the interface contract; callers re-invoke on their next cycle.
                    return Optional.empty();
                }

                Object existing = objectMapper.readValue(existingJson, Object.class);
                // Cached nulls must surface exactly like get() surfaces them:
                // as the NullValueMarker instance, not the raw marker map
                if (allowNullValues && NullValueMarker.isMarker(existing)) {
                    return Optional.of(NullValueMarker.getInstance());
                }
                return Optional.of(existing);
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new PgCacheException("Failed to putIfAbsent value in cache", e);
        }
    }

    // ==================== Read-through with single-flight (v1.9.0) ====================

    @Override
    public <T> T getOrCompute(String key, Class<T> clazz, Duration ttl, Supplier<T> loader) {
        return getOrCompute(key, clazz, ttl, TTLPolicy.ABSOLUTE, loader);
    }

    @Override
    public <T> T getOrCompute(String key, Class<T> clazz, Duration ttl, TTLPolicy policy, Supplier<T> loader) {
        validateKey(key);
        if (loader == null) {
            throw new PgCacheException("Loader cannot be null");
        }
        if (policy == null) {
            throw new PgCacheException("TTL policy cannot be null");
        }
        Integer ttlSeconds = ttl != null ? normalizeTtlSeconds(ttl) : null;

        // Fast path: no lock while the value is warm
        try {
            Optional<T> cached = get(key, clazz);
            if (cached.isPresent()) {
                T value = cached.get();
                return value instanceof NullValueMarker ? null : value;
            }
        } catch (PgCacheException e) {
            logger.warn("getOrCompute read failed for key '{}', falling back to direct load: {}", key, e.getMessage());
            return loader.get();
        }

        try (Connection conn = getValidatedConnection()) {
            conn.setAutoCommit(false);
            boolean committed = false;
            try {
                acquireKeyLock(conn, key);

                // Re-check under the lock: a concurrent loader may have stored
                // the value while we were waiting
                String existingJson = readLiveValueJson(conn, key);
                if (existingJson != null) {
                    try {
                        T value = deserializeMarkerAware(existingJson, clazz);
                        conn.commit();
                        committed = true;
                        return value;
                    } catch (JsonProcessingException corrupt) {
                        // undeserializable row is as good as a miss — recompute and overwrite
                        logger.warn("getOrCompute found undeserializable value for key '{}', recomputing: {}",
                                key, corrupt.getMessage());
                    }
                }

                T loaded = loader.get();

                if (loaded == null && !allowNullValues) {
                    return null; // rollback in finally releases the lock; nothing to store
                }

                // Store failures must not re-run or hide the already computed value
                try {
                    writeEntry(conn, key, normalizeValue(loaded), ttlSeconds, policy);
                    conn.commit();
                    committed = true;
                    putCount.incrementAndGet();
                    invalidateSizeCache();
                    fireOnPut(key, loaded);
                } catch (SQLException | JsonProcessingException storeFailure) {
                    logger.warn("getOrCompute could not store computed value for key '{}', returning it uncached: {}",
                            key, storeFailure.getMessage());
                }
                return loaded;
            } finally {
                if (!committed) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackFailure) {
                        logger.debug("Rollback failed in getOrCompute for key '{}': {}", key, rollbackFailure.getMessage());
                    }
                }
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException restoreFailure) {
                    logger.debug("Failed to restore autocommit before pool return: {}", restoreFailure.getMessage());
                }
            }
        } catch (SQLException e) {
            // Lock acquisition or the re-check failed before the loader ran:
            // reads fail open, compute directly and return uncached
            logger.warn("getOrCompute falling back to direct load for key '{}': {}", key, e.getMessage());
            return loader.get();
        }
    }

    /**
     * Blocks on a transaction-scoped advisory lock derived from the table name
     * and key. Released automatically on commit or rollback, so a crashed or
     * failed loader can never leave the key locked.
     */
    private void acquireKeyLock(Connection conn, String key) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            stmt.setString(1, tableName + ":" + key);
            stmt.executeQuery().close();
        }
    }

    /** Reads the raw JSON of a live (non-expired) row on the given connection, or null. */
    private String readLiveValueJson(Connection conn, String key) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT value FROM " + tableName + " WHERE key = ? AND " + NOT_EXPIRED_WHERE_CLAUSE)) {
            stmt.setString(1, key);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Deserializes cached JSON, mapping a stored null marker back to Java null. */
    private <T> T deserializeMarkerAware(String json, Class<T> clazz) throws JsonProcessingException {
        if (allowNullValues) {
            Object raw = objectMapper.readValue(json, Object.class);
            if (NullValueMarker.isMarker(raw)) {
                return null;
            }
        }
        return objectMapper.readValue(json, clazz);
    }

    /** Upserts one entry on the given connection; ttlSeconds null means permanent. */
    private void writeEntry(Connection conn, String key, Object valueToStore,
                            Integer ttlSeconds, TTLPolicy policy) throws SQLException, JsonProcessingException {
        String sql = "INSERT INTO " + tableName +
                     " (key, value, updated_at, ttl_seconds, ttl_policy, last_accessed) " +
                     "VALUES (?, ?::jsonb, now(), ?, ?, now()) " +
                     "ON CONFLICT (key) DO UPDATE SET " +
                     "value = EXCLUDED.value, updated_at = EXCLUDED.updated_at, " +
                     "ttl_seconds = EXCLUDED.ttl_seconds, ttl_policy = EXCLUDED.ttl_policy, " +
                     "last_accessed = EXCLUDED.last_accessed";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, key);
            stmt.setString(2, objectMapper.writeValueAsString(valueToStore));
            if (ttlSeconds != null) {
                stmt.setInt(3, ttlSeconds);
                stmt.setString(4, policy.name());
            } else {
                stmt.setNull(3, java.sql.Types.INTEGER);
                stmt.setNull(4, java.sql.Types.VARCHAR);
            }
            stmt.executeUpdate();
        }
    }

    // ==================== Batch Operations (v1.3.0) ====================

    @Override
    public <T> Map<String, T> getAll(Collection<String> keys, Class<T> clazz) {
        if (keys == null || keys.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, T> results = new HashMap<>();
        List<String> keyList = new ArrayList<>(keys);

        // Build SQL with placeholders
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT key, value, updated_at, ttl_seconds, ttl_policy, last_accessed FROM ");
        sql.append(tableName);
        sql.append(" WHERE key IN (");
        for (int i = 0; i < keyList.size(); i++) {
            sql.append(i > 0 ? ", ?" : "?");
        }
        sql.append(") AND ");
        sql.append(NOT_EXPIRED_WHERE_CLAUSE);

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < keyList.size(); i++) {
                stmt.setString(i + 1, keyList.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("key");
                    String jsonValue = rs.getString("value");

                    // Deserialize value
                    try {
                        Object rawResult = objectMapper.readValue(jsonValue, Object.class);

                        // Handle null marker
                        if (allowNullValues && NullValueMarker.isMarker(rawResult)) {
                            @SuppressWarnings("unchecked")
                            T marker = (T) NullValueMarker.getInstance();
                            results.put(key, marker);
                            continue;
                        }

                        T value = objectMapper.readValue(jsonValue, clazz);
                        results.put(key, value);
                    } catch (JsonProcessingException e) {
                        logger.warn("Failed to deserialize value for key '{}': {}", key, e.getMessage());
                    }
                }
            }

            // Track statistics: hits for found keys, misses for not found
            int hits = results.size();
            int misses = keyList.size() - hits;
            hitCount.addAndGet(hits);
            missCount.addAndGet(misses);

            return results;

        } catch (SQLException e) {
            throw new PgCacheException("Failed to get multiple values from cache", e);
        }
    }

    @Override
    public <T> void putAll(Map<String, T> entries, Duration ttl) {
        putAll(entries, ttl, TTLPolicy.ABSOLUTE);
    }

    @Override
    public <T> void putAll(Map<String, T> entries, Duration ttl, TTLPolicy policy) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        if (policy == null) {
            throw new PgCacheException("TTL policy cannot be null");
        }

        int ttlSeconds = normalizeTtlSeconds(ttl);

        String sql = "INSERT INTO " + tableName +
                     " (key, value, updated_at, ttl_seconds, ttl_policy, last_accessed) " +
                     "VALUES (?, ?::jsonb, now(), ?, ?, now()) " +
                     "ON CONFLICT (key) DO UPDATE SET " +
                     "value = EXCLUDED.value, updated_at = now(), ttl_seconds = EXCLUDED.ttl_seconds, " +
                     "ttl_policy = EXCLUDED.ttl_policy, last_accessed = now()";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (Map.Entry<String, T> entry : entries.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (key == null || key.isEmpty()) {
                    continue;
                }

                Object valueToStore = normalizeValue(value);

                String jsonValue = objectMapper.writeValueAsString(valueToStore);

                stmt.setString(1, key);
                stmt.setString(2, jsonValue);
                stmt.setInt(3, ttlSeconds);
                stmt.setString(4, policy.name());
                stmt.addBatch();
            }

            int[] results = executeBatchAtomically(conn, stmt);
            putCount.addAndGet(results.length);
            invalidateSizeCache();

        } catch (SQLException | JsonProcessingException e) {
            throw new PgCacheException("Failed to put multiple values in cache", e);
        }
    }

    /**
     * Executes a batch inside an explicit transaction so a mid-batch failure
     * leaves no partial prefix written (MSET semantics). pgJDBC happens to
     * pipeline small batches atomically, but large batches are flushed in
     * chunks — without a transaction each flushed chunk would commit.
     */
    private int[] executeBatchAtomically(Connection conn, PreparedStatement stmt) throws SQLException {
        conn.setAutoCommit(false);
        try {
            int[] results = stmt.executeBatch();
            conn.commit();
            return results;
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException restoreFailure) {
                // connection is being returned to the pool; the pool resets state
                logger.debug("Failed to restore autocommit before pool return: {}", restoreFailure.getMessage());
            }
        }
    }

    @Override
    public <T> void putAll(Map<String, T> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO " + tableName +
                     " (key, value, updated_at, ttl_seconds) " +
                     "VALUES (?, ?::jsonb, now(), NULL) " +
                     "ON CONFLICT (key) DO UPDATE SET " +
                     "value = EXCLUDED.value, updated_at = now(), ttl_seconds = NULL";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            for (Map.Entry<String, T> entry : entries.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (key == null || key.isEmpty()) {
                    continue;
                }

                Object valueToStore = normalizeValue(value);

                String jsonValue = objectMapper.writeValueAsString(valueToStore);

                stmt.setString(1, key);
                stmt.setString(2, jsonValue);
                stmt.addBatch();
            }

            int[] results = executeBatchAtomically(conn, stmt);
            putCount.addAndGet(results.length);
            invalidateSizeCache();

        } catch (SQLException | JsonProcessingException e) {
            throw new PgCacheException("Failed to put multiple values in cache", e);
        }
    }

    @Override
    public int evictAll(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return 0;
        }

        List<String> keyList = new ArrayList<>(keys);

        StringBuilder sql = new StringBuilder();
        sql.append("DELETE FROM ").append(tableName).append(" WHERE key IN (");
        for (int i = 0; i < keyList.size(); i++) {
            sql.append(i > 0 ? ", ?" : "?");
        }
        sql.append(")");

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < keyList.size(); i++) {
                stmt.setString(i + 1, keyList.get(i));
            }

            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                evictionCount.addAndGet(deleted);
                invalidateSizeCache();
            }
            return deleted;

        } catch (SQLException e) {
            throw new PgCacheException("Failed to evict multiple keys from cache", e);
        }
    }

    // ==================== Pattern Operations (v1.3.0) ====================

    @Override
    public int evictByPattern(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new PgCacheException("Pattern cannot be null or empty");
        }

        String sql = "DELETE FROM " + tableName + " WHERE key LIKE ? ESCAPE '\\'";

        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, pattern);
            int deleted = stmt.executeUpdate();

            if (deleted > 0) {
                evictionCount.addAndGet(deleted);
                invalidateSizeCache();
                logger.debug("Evicted {} entries matching pattern '{}'", deleted, pattern);
            }

            return deleted;

        } catch (SQLException e) {
            throw new PgCacheException("Failed to evict entries by pattern: " + pattern, e);
        }
    }

    // ==================== Cache Statistics (v1.3.0) ====================

    @Override
    public CacheStatistics getStatistics() {
        return new CacheStatistics(
            hitCount.get(),
            missCount.get(),
            putCount.get(),
            evictionCount.get()
        );
    }

    @Override
    public void resetStatistics() {
        hitCount.set(0);
        missCount.set(0);
        putCount.set(0);
        evictionCount.set(0);
        logger.debug("Cache statistics reset");
    }

    // ==================== Key Operations (v1.6.0) ====================

    @Override
    public Collection<String> getKeys(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            throw new PgCacheException("Pattern cannot be null or empty");
        }

        String sql = "SELECT key FROM " + tableName +
                     " WHERE key LIKE ? ESCAPE '\\' AND " + NOT_EXPIRED_WHERE_CLAUSE;

        List<String> keys = new ArrayList<>();
        try (Connection conn = getValidatedConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, pattern);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString("key"));
                }
            }
            return keys;
        } catch (SQLException e) {
            throw new PgCacheException("Failed to get keys by pattern: " + pattern, e);
        }
    }

    @Override
    public Collection<String> getAllKeys() {
        return getKeys("%");
    }

    // ==================== Async Operations (v1.6.0) ====================

    @Override
    public <T> CompletableFuture<Optional<T>> getAsync(String key, Class<T> clazz) {
        return CompletableFuture.supplyAsync(() -> get(key, clazz), asyncExecutor);
    }

    @Override
    public <T> CompletableFuture<Void> putAsync(String key, T value, Duration ttl) {
        return CompletableFuture.runAsync(() -> put(key, value, ttl), asyncExecutor);
    }

    @Override
    public <T> CompletableFuture<Void> putAsync(String key, T value, Duration ttl, TTLPolicy policy) {
        return CompletableFuture.runAsync(() -> put(key, value, ttl, policy), asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> evictAsync(String key) {
        return CompletableFuture.runAsync(() -> evict(key), asyncExecutor);
    }

    private int normalizeTtlSeconds(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new PgCacheException("TTL must be positive");
        }

        long ttlSeconds = ttl.getSeconds();
        if (ttlSeconds <= 0) {
            throw new PgCacheException("TTL must be at least 1 second");
        }
        if (ttlSeconds > Integer.MAX_VALUE) {
            throw new PgCacheException("TTL exceeds maximum supported value");
        }

        return (int) ttlSeconds;
    }

    private static boolean isValidTableName(String tableName) {
        String[] segments = tableName.split("\\.", -1);
        if (segments.length == 0 || segments.length > 2) {
            return false;
        }

        for (String segment : segments) {
            if (!segment.matches(SQL_IDENTIFIER_PATTERN)) {
                return false;
            }
        }

        return true;
    }

    private static void validateKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new PgCacheException("Cache key cannot be null or empty");
        }
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            if (!allowNullValues) {
                throw new PgCacheException("Cache value cannot be null (allowNullValues=false)");
            }
            return NullValueMarker.getInstance();
        }
        return value;
    }
}
