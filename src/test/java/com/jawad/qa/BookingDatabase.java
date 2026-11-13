package com.jawad.qa;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

final class BookingDatabase implements AutoCloseable {
    private final String url = "jdbc:h2:mem:booking_" + UUID.randomUUID();
    private final Connection anchor;

    BookingDatabase() throws IOException {
        try {
            anchor = connect();
            try (var statement = anchor.createStatement()) {
                statement.execute("CREATE TABLE booking (id UUID PRIMARY KEY, name VARCHAR(80) NOT NULL)");
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

    Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    void insert(UUID id, String name) throws SQLException {
        try (var connection = connect();
             var statement = connection.prepareStatement("INSERT INTO booking (id, name) VALUES (?, ?)")) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.executeUpdate();
        }
    }

    String find(UUID id) throws SQLException {
        try (var connection = connect();
             var statement = connection.prepareStatement("SELECT name FROM booking WHERE id = ?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString("name") : null;
            }
        }
    }

    boolean ready() {
        try (var connection = connect(); var statement = connection.createStatement()) {
            statement.executeQuery("SELECT COUNT(*) FROM booking").close();
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
