package com.jawad.qa;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseValidationTest {
    @Test
    void validBookingPersistsAndRejectedBookingDoesNotWriteARow() throws Exception {
        try (var app = new BookingApp()) {
            String id = given().baseUri(app.url()).contentType("application/json")
                .body(Map.of("name", "  O'Neil  ")).post("/api/bookings").then()
                .statusCode(201).extract().path("id");
            given().baseUri(app.url()).contentType("application/json")
                .body(Map.of("name", "x".repeat(81))).post("/api/bookings").then().statusCode(400);

            try (var connection = app.database().connect();
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT id, name FROM booking")) {
                assertTrue(rows.next());
                assertEquals(UUID.fromString(id), rows.getObject("id", UUID.class));
                assertEquals("O'Neil", rows.getString("name"));
                assertFalse(rows.next(), "The rejected booking must not produce a row");
            }
        }
    }
}
