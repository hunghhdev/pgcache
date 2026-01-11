package io.github.hunghhdev.pgcache.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PgCacheStoreTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    private PgCacheStore cacheStore;
    private ObjectMapper realObjectMapper;

    @BeforeEach
    void setUp() throws SQLException {
        // Use lenient() to prevent "unnecessary stubbing" errors
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        // Use a real ObjectMapper for serialization tests
        realObjectMapper = new ObjectMapper();

        // Create a PgCacheStore that doesn't auto-create the table for unit tests
        cacheStore = PgCacheStore.builder()
                .dataSource(dataSource)
                .objectMapper(realObjectMapper)
                .autoCreateTable(false)
                .build();
    }

    @Test
    void testInitializeTable() throws SQLException {
        // Arrange - Mock DatabaseMetaData
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getTables(null, null, "pgcache_store", new String[]{"TABLE"}))
                .thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false); // Table doesn't exist
        
        PgCacheStore store = PgCacheStore.builder()
                .dataSource(dataSource)
                .autoCreateTable(true)
                .build();

        // Verify
        verify(statement).execute(contains("CREATE UNLOGGED TABLE IF NOT EXISTS pgcache_store"));
    }

    @Test
    void testGet_WhenKeyExists_AndNotExpired() throws Exception {
        // Arrange
        String key = "test-key";
        TestObject expectedObject = new TestObject("test-value", 123);
        String jsonValue = realObjectMapper.writeValueAsString(expectedObject);

        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("value")).thenReturn(jsonValue);
        when(resultSet.getTimestamp("updated_at")).thenReturn(
            Timestamp.from(Instant.now().minusSeconds(30)));
        when(resultSet.getObject("ttl_seconds", Integer.class)).thenReturn(60);

        // Act
        Optional<TestObject> result = cacheStore.get(key, TestObject.class);

        // Assert
        assertTrue(result.isPresent());
        assertEquals("test-value", result.get().getName());
        assertEquals(123, result.get().getValue());

        // Verify
        verify(preparedStatement).setString(1, key);
    }

    @Test
    void testGet_WhenKeyDoesNotExist() throws Exception {
        // Arrange
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        // Act
        Optional<TestObject> result = cacheStore.get("non-existent-key", TestObject.class);

        // Assert
        assertFalse(result.isPresent());
    }

    @Test
    void testGet_WhenKeyIsExpired() throws Exception {
        // Arrange
        String key = "expired-key";
        TestObject expectedObject = new TestObject("expired-value", 456);
        String jsonValue = realObjectMapper.writeValueAsString(expectedObject);

        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("value")).thenReturn(jsonValue);
        when(resultSet.getTimestamp("updated_at")).thenReturn(
            Timestamp.from(Instant.now().minusSeconds(120)));
        when(resultSet.getObject("ttl_seconds", Integer.class)).thenReturn(60);

        // Create a second prepared statement for the evict call
        PreparedStatement evictStatement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("DELETE"))).thenReturn(evictStatement);

        // Act
        Optional<TestObject> result = cacheStore.get(key, TestObject.class);

        // Assert
        assertFalse(result.isPresent());

        // Verify evict was called
        verify(evictStatement).setString(1, key);
        verify(evictStatement).executeUpdate();
    }

    @Test
    void testPut() throws Exception {
        // Arrange
        String key = "test-key";
        TestObject value = new TestObject("test-value", 123);
        Duration ttl = Duration.ofMinutes(5);

        // Act
        cacheStore.put(key, value, ttl);

        // Assert & Verify
        verify(preparedStatement).setString(1, key);

        // Capture the JSON string
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(preparedStatement).setString(eq(2), jsonCaptor.capture());

        // Verify JSON is correct
        TestObject deserializedObject = realObjectMapper.readValue(jsonCaptor.getValue(), TestObject.class);
        assertEquals("test-value", deserializedObject.getName());
        assertEquals(123, deserializedObject.getValue());

        // Verify TTL
        verify(preparedStatement).setInt(3, 300); // 5 minutes = 300 seconds
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void testPutWithoutTTL() throws Exception {
        // Arrange
        String key = "test-key";
        TestObject value = new TestObject("test-value", 123);
        String jsonValue = realObjectMapper.writeValueAsString(value);

        // Act
        cacheStore.put(key, value);

        // Assert
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection, times(1)).prepareStatement(sqlCaptor.capture());
        
        String capturedSql = sqlCaptor.getValue();
        assertTrue(capturedSql.contains("ttl_seconds) VALUES (?, ?::jsonb, now(), NULL)"));
        
        verify(preparedStatement).setString(1, key);
        verify(preparedStatement).setString(2, jsonValue);
        verify(preparedStatement, never()).setInt(anyInt(), anyInt()); // No TTL parameter
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void testGetWithNullTTL() throws Exception {
        // Arrange
        String key = "test-key";
        TestObject expectedObject = new TestObject("test-value", 123);
        String jsonValue = realObjectMapper.writeValueAsString(expectedObject);

        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("value")).thenReturn(jsonValue);
        when(resultSet.getTimestamp("updated_at")).thenReturn(
            Timestamp.from(Instant.now().minusSeconds(3600))); // Old entry
        when(resultSet.getObject("ttl_seconds", Integer.class)).thenReturn(null); // No TTL

        // Act
        Optional<TestObject> result = cacheStore.get(key, TestObject.class);

        // Assert - should return the value since NULL TTL means permanent
        assertTrue(result.isPresent());
        assertEquals("test-value", result.get().getName());
        assertEquals(123, result.get().getValue());
    }

    @Test
    void testEvict() throws Exception {
        // Arrange
        String key = "key-to-evict";

        // Act
        cacheStore.evict(key);

        // Verify
        verify(preparedStatement).setString(1, key);
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void testClear() throws Exception {
        // Act
        cacheStore.clear();

        // Verify
        verify(statement).executeUpdate(contains("DELETE FROM pgcache_store"));
    }

    @Test
    void testSize() throws Exception {
        // Arrange
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(42);

        // Act
        int size = cacheStore.size();

        // Assert
        assertEquals(42, size);

        // Verify
        verify(statement).executeQuery(contains("COUNT(*)"));
        verify(statement).executeQuery(contains("ttl_seconds * interval '1 second'"));
    }

    // Test object class for serialization/deserialization
    static class TestObject {
        private String name;
        private int value;

        // Default constructor for Jackson
        public TestObject() {
        }

        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }

    // ==================== v1.6.0 Feature Tests ====================

    @Test
    void testContainsKey() throws SQLException {
        // Arrange
        String key = "existing-key";
        when(connection.prepareStatement(argThat(sql -> sql.contains("SELECT 1 FROM")))).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        // Act
        boolean exists = cacheStore.containsKey(key);

        // Assert
        assertTrue(exists);
        verify(preparedStatement).setString(1, key);
    }

    @Test
    void testGetKeys() throws SQLException {
        // Arrange
        String pattern = "user:%";
        when(connection.prepareStatement(argThat(sql -> sql.contains("SELECT key FROM")))).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("key")).thenReturn("user:1", "user:2");

        // Act
        Collection<String> keys = cacheStore.getKeys(pattern);

        // Assert
        assertEquals(2, keys.size());
        assertTrue(keys.contains("user:1"));
        assertTrue(keys.contains("user:2"));
    }

    @Test
    void testAsyncGet() throws Exception {
        // Arrange
        String key = "async-key";
        TestObject expected = new TestObject("Async", 1);
        String json = realObjectMapper.writeValueAsString(expected);

        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("value")).thenReturn(json);
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.now()));
        when(resultSet.getObject("ttl_seconds", Integer.class)).thenReturn(60);

        // Act
        CompletableFuture<Optional<TestObject>> future = cacheStore.getAsync(key, TestObject.class);
        Optional<TestObject> result = future.get(1, TimeUnit.SECONDS);

        // Assert
        assertTrue(result.isPresent());
        assertEquals("Async", result.get().getName());
    }

    @Test
    void testEventListener() throws SQLException {
        // Arrange
        CacheEventListener listener = mock(CacheEventListener.class);
        PgCacheStore storeWithListener = PgCacheStore.builder()
                .dataSource(dataSource)
                .objectMapper(realObjectMapper)
                .autoCreateTable(false)
                .addEventListener(listener)
                .build();

        String key = "event-key";
        TestObject value = new TestObject("Event", 1);
        
        // Mock connection for the new store instance
        // Note: In a real unit test this is tricky because builder creates new instance.
        // But here we reuse the mocked dataSource which returns mocked connection.
        // The issue is connection.prepareStatement() needs to return a valid statement for the PUT.
        
        // We need to ensure when storeWithListener calls put, it gets the mocked statement
        // The existing mock setup in setUp() might handle this if it's broad enough
        // But setUp() mocks calls on the 'connection' mock object. 
        // storeWithListener will call dataSource.getConnection().
        
        // Act
        storeWithListener.put(key, value, Duration.ofMinutes(1));

        // Assert
        verify(listener).onPut(eq(key), any(TestObject.class));
    }
}
