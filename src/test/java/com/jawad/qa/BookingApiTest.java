package com.jawad.qa;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BookingApiTest {
    @Test
    void createsAndRetrievesBooking() throws Exception {
        try (var app = new BookingApp()) {
            var created = given().baseUri(app.url()).contentType("application/json")
                .body(Map.of("name", "Ada")).post("/api/bookings")
                .then().statusCode(201).body("name", equalTo("Ada")).extract();
            String id = created.path("id");
            assertEquals("/api/bookings/" + id, created.header("Location"));
            given().baseUri(app.url()).get(created.header("Location")).then()
                .statusCode(200).body("id", equalTo(id)).body("name", equalTo("Ada"));
        }
    }

    @Test
    void rejectsInvalidPayloadAndReturnsNotFoundForUnknownBooking() throws Exception {
        try (var app = new BookingApp()) {
            given().baseUri(app.url()).contentType("application/json")
                .body(Map.of("name", " ")).post("/api/bookings").then().statusCode(400);
            given().baseUri(app.url()).contentType("application/json")
                .body("{").post("/api/bookings").then().statusCode(400);
            given().baseUri(app.url()).get("/api/bookings/00000000-0000-0000-0000-000000000001")
                .then().statusCode(404);
        }
    }
}
