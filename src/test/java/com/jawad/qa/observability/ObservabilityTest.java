package com.jawad.qa.observability;

import com.jawad.booking.BookingApp;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

class ObservabilityTest {
    @Test
    void healthRequestIdsAndMetricsReflectApiTraffic() throws Exception {
        try (var app = new BookingApp()) {
            given().baseUri(app.url()).get("/health").then().statusCode(200)
                .body("status", equalTo("UP"));
            String first = given().baseUri(app.url()).contentType("application/json")
                .body("{\"name\":\"Ada\"}").post("/api/bookings").then().statusCode(201)
                .extract().header("X-Request-Id");
            String second = given().baseUri(app.url()).get("/api/bookings/invalid").then()
                .statusCode(400).extract().header("X-Request-Id");
            assertNotEquals(UUID.fromString(first), UUID.fromString(second));

            String metrics = "";
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                metrics = given().baseUri(app.url()).get("/metrics").then().statusCode(200)
                    .extract().asString();
                if (metrics.contains("booking_api_requests_total 2\n")) {
                    break;
                }
                Thread.sleep(20);
            }
            assertTrue(metrics.contains("booking_api_requests_total 2\n"), metrics);
            assertTrue(metrics.contains("booking_api_client_errors_total 1\n"), metrics);
            assertTrue(metrics.contains("booking_api_server_errors_total 0\n"), metrics);
        }
    }

    @Test
    void databaseFailureChangesReadinessAndReturnsGenericError() throws Exception {
        try (var app = new BookingApp(); var connection = app.database().connect();
             var statement = connection.createStatement()) {
            statement.execute("DROP TABLE booking");
            given().baseUri(app.url()).get("/health").then().statusCode(503)
                .body("status", equalTo("DOWN"));
            given().baseUri(app.url()).contentType("application/json")
                .body("{\"name\":\"Ada\"}").post("/api/bookings").then().statusCode(500)
                .body("error", equalTo("Booking storage unavailable"));
        }
    }
}
