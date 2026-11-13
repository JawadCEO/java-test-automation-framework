package com.jawad.qa.db;

import com.jawad.booking.BookingApp;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies through a separate JDBC connection that the API leaves the database in the expected state. */
@Tag("db")
class DatabaseValidationTest {
    private BookingApp app;

    @BeforeEach
    void start() throws Exception {
        app = new BookingApp();
    }

    @AfterEach
    void stop() {
        app.close();
    }

    private String create(String name) {
        return given().baseUri(app.url()).contentType("application/json").body(Map.of("name", name))
            .post("/api/bookings").then().statusCode(201).extract().path("id");
    }

    private long count(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    @Test
    void validBookingPersistsAndRejectedBookingDoesNotWriteARow() throws Exception {
        String id = create("  O'Neil  ");
        given().baseUri(app.url()).contentType("application/json")
            .body(Map.of("name", "x".repeat(81))).post("/api/bookings").then().statusCode(400);

        try (var connection = app.database().connect();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, name, created_at FROM booking")) {
            assertTrue(rows.next());
            assertEquals(UUID.fromString(id), rows.getObject("id", UUID.class));
            assertEquals("O'Neil", rows.getString("name"));
            assertNotNull(rows.getTimestamp("created_at"));
            assertFalse(rows.next(), "The rejected booking must not produce a row");
        }
    }

    @Test
    void deleteRemovesExactlyOneRow() throws Exception {
        String keep = create("Keep");
        String remove = create("Remove");
        given().baseUri(app.url()).delete("/api/bookings/" + remove).then().statusCode(204);
        try (var connection = app.database().connect()) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM booking"));
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM booking WHERE id = ?", UUID.fromString(keep)));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM booking WHERE id = ?", UUID.fromString(remove)));
        }
    }

    @Test
    void listTotalMatchesRowCount() throws Exception {
        IntStream.range(0, 7).forEach(i -> create("Row " + i));
        int total = given().baseUri(app.url()).get("/api/bookings").then().statusCode(200)
            .extract().path("total");
        try (var connection = app.database().connect()) {
            assertEquals(count(connection, "SELECT COUNT(*) FROM booking"), total);
        }
    }

    @Test
    void schemaMatchesExpectedColumnsAndConstraints() throws Exception {
        try (var connection = app.database().connect();
             var statement = connection.prepareStatement("""
                 SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH
                 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'BOOKING' ORDER BY ORDINAL_POSITION""");
             var rows = statement.executeQuery()) {
            List<String> columns = new java.util.ArrayList<>();
            while (rows.next()) {
                columns.add(rows.getString(1) + ":" + rows.getString(2) + ":" + rows.getString(3)
                    + ":" + rows.getObject(4));
            }
            assertEquals(List.of("ID:UUID:NO:null", "NAME:CHARACTER VARYING:NO:80",
                "CREATED_AT:TIMESTAMP WITH TIME ZONE:NO:null"), columns);
        }
        try (var connection = app.database().connect()) {
            assertEquals(1, count(connection, """
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS
                WHERE TABLE_NAME = 'BOOKING' AND CONSTRAINT_TYPE = 'PRIMARY KEY'"""));
        }
    }

    @Test
    void databaseRejectsDuplicateIdsAndEmptyNames() throws Exception {
        String id = create("Original");
        try (var connection = app.database().connect()) {
            var duplicate = assertThrows(SQLException.class, () -> {
                try (var insert = connection.prepareStatement("INSERT INTO booking (id, name) VALUES (?, ?)")) {
                    insert.setObject(1, UUID.fromString(id));
                    insert.setString(2, "Copy");
                    insert.executeUpdate();
                }
            });
            assertEquals("23505", duplicate.getSQLState(), "Unique violation expected");

            var empty = assertThrows(SQLException.class, () -> {
                try (var insert = connection.prepareStatement("INSERT INTO booking (id, name) VALUES (?, '')")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.executeUpdate();
                }
            });
            assertEquals("23513", empty.getSQLState(), "Check constraint violation expected");
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM booking"));
        }
    }

    @Test
    void concurrentCreatesProduceOneUniqueRowEach() throws Exception {
        int bookings = 50;
        List<Callable<String>> calls = IntStream.range(0, bookings)
            .<Callable<String>>mapToObj(i -> () -> create("Concurrent " + i)).toList();
        Set<String> ids = new HashSet<>();
        try (var pool = new AutoClosingPool(Executors.newFixedThreadPool(10))) {
            for (var future : pool.executor().invokeAll(calls)) {
                ids.add(future.get());
            }
        }
        assertEquals(bookings, ids.size());
        try (var connection = app.database().connect()) {
            assertEquals(bookings, count(connection, "SELECT COUNT(*) FROM booking"));
            assertEquals(bookings, count(connection, "SELECT COUNT(DISTINCT name) FROM booking"));
        }
    }

    @Test
    void storedNamesAreParameterizedNotInterpolated() throws Exception {
        String hostile = "Robert'); DROP TABLE booking;--";
        String id = create(hostile);
        try (var connection = app.database().connect()) {
            assertEquals(1, count(connection, "SELECT COUNT(*) FROM booking WHERE id = ? AND name = ?",
                UUID.fromString(id), hostile));
        }
    }

    private record AutoClosingPool(java.util.concurrent.ExecutorService executor) implements AutoCloseable {
        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
