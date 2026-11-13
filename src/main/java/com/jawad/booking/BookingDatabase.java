package com.jawad.booking;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** H2 in-memory store. Each instance owns an isolated database so tests never share rows. */
public final class BookingDatabase implements AutoCloseable {
    private final String url = "jdbc:h2:mem:booking_" + UUID.randomUUID();
    private final Connection anchor;

    public BookingDatabase() throws IOException {
        try {
            anchor = connect();
            try (var statement = anchor.createStatement()) {
                statement.execute("""
                    CREATE TABLE booking (
                      id UUID PRIMARY KEY,
                      name VARCHAR(80) NOT NULL CHECK (CHAR_LENGTH(name) > 0),
                      created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
                    )""");
            } catch (SQLException failure) {
                try {
                    anchor.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IOException("Cannot initialize booking database", failure);
        }
    }

    /** Opens an independent JDBC connection, which tests use to verify state behind the API. */
    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    public void insert(UUID id, String name) throws SQLException {
        try (var connection = connect();
             var statement = connection.prepareStatement("INSERT INTO booking (id, name) VALUES (?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.executeUpdate();
        }
    }

    public String find(UUID id) throws SQLException {
        try (var connection = connect();
             var statement = connection.prepareStatement("SELECT name FROM booking WHERE id = ?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString("name") : null;
            }
        }
    }

    public boolean delete(UUID id) throws SQLException {
        try (var connection = connect();
             var statement = connection.prepareStatement("DELETE FROM booking WHERE id = ?")) {
            statement.setObject(1, id);
            return statement.executeUpdate() == 1;
        }
    }

    /** Most recent bookings first; ties on the timestamp fall back to the ID for a stable order. */
    public List<Map<String, Object>> list(int limit) throws SQLException {
        try (var connection = connect();
             var statement = connection.prepareStatement(
                 "SELECT id, name FROM booking ORDER BY created_at DESC, id LIMIT ?")) {
            statement.setInt(1, limit);
            try (var rows = statement.executeQuery()) {
                List<Map<String, Object>> page = new ArrayList<>();
                while (rows.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rows.getObject("id", UUID.class));
                    row.put("name", rows.getString("name"));
                    page.add(row);
                }
                return page;
            }
        }
    }

    public long count() throws SQLException {
        try (var connection = connect(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM booking")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    public boolean ready() {
        try {
            count();
            return true;
        } catch (SQLException unavailable) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            anchor.close();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot close booking database", failure);
        }
    }
}
